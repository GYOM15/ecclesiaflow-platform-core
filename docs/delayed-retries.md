# Reprises à délais des consommateurs à effet externe

Rang 3 du plan mobile, lot 5 (`ARCHITECTURE-MOBILE.md` §3.11, `PLAN-RANG-3.md`, point 1). Les
consommateurs dont l'effet sort du module (auth vers Keycloak, chat, communication) survivent à une
panne de plusieurs heures de leur cible, sans affaiblir la vérification des signatures.

## Topologie

- **Adhésion par file.** Le module déclare un bean `DelayedRetryTopology("<file>")`. Sans ce bean,
  rien ne change : `PlatformDelayedRetryAutoConfiguration` reste inactive.
- **Étages.** `<file>.retry.1m`, `.retry.5m`, `.retry.30m`, `.retry.2h` : files durables sans
  consommateur, `x-message-ttl` égal au délai, rebut vers l'échange par défaut sous le nom de la file
  consommatrice. Le délai est dans le nom : RabbitMQ ne change jamais les arguments d'une file durable,
  un autre délai est donc une autre file.
- **Parcours d'un échec.** La reprise en mémoire du conteneur s'épuise, puis
  `DelayedRetryMessageRecoverer` (à la place de `RejectAndDontRequeueRecoverer`) publie une copie dans
  l'étage suivant, `mandatory` et confirmée par le courtier, avant l'accusé de l'original. Échec du
  dernier étage, cause qu'aucune attente ne corrige (message illisible, rejet explicite) ou copie
  refusée par le courtier : rejet sans remise en file, donc la file de rebut existante. Le démarrage
  échoue si la file n'a pas de route de rebut (`DeadLetterRoute` ou arguments), si la fabrique de
  connexions ne confirme pas, ou si les événements sont signés sans secret pour tamponner.
- **`ProcessedDomainEvent` après l'effet.** `ExternalEffectDeduplicator.applyOnce(type, eventId,
  effet)` applique l'effet puis enregistre l'événement, sur le port `ProcessedDomainEventStore` que
  chaque module implémente sur sa table. Un effet en échec ne laisse aucune trace : la reprise le
  rejoue. Une panne entre l'effet et l'écriture le rejoue aussi : l'effet reste idempotent.
- **Mesures**, à côté de `ecclesiaflow_domain_events_dlq_depth` :
  `ecclesiaflow_domain_events_retry_depth{queue}` (profondeur de chaque étage, lue à chaque collecte,
  -1 si inconnue) et `ecclesiaflow_domain_events_retry_total{queue,outcome}` (`scheduled`,
  `exhausted`, `not_retryable`, `publish_failed`). La jauge de rebut ignore les étages.
- **Route reçue.** Une relivraison arrive par l'échange par défaut, sous le nom de la file : un
  consommateur ne s'aiguille jamais sur la clé reçue. La route d'origine est dans
  `x-ef-original-routing-key`, garantie par le tampon.
- **Limite connue.** Le passage d'un étage à la file est un rebut interne du courtier, « au plus une
  fois » pour une file classique. Sur un nœud unique (le Pi), c'est une publication locale. En grappe
  (migration vers Kubernetes), passer les étages en files quorum avec
  `dead-letter-strategy=at-least-once` et `overflow=reject-publish`.

## Point 1 : signatures et reprises à délais

**Le problème.** Sous `EVENTS_VERIFY_SIGNATURES=true`, `DomainEventVerifier` refuse un message signé
il y a plus de 5 minutes (`REJECT_STALE`). Toute reprise après l'étage de 5 minutes partirait au rebut.

**`x-death` ne peut pas servir.** Vérifié pour RabbitMQ 3.13.7 :

- la PR rabbitmq-server #11339, « Interpret x-death header from AMQP 0.9.1 client », livrée en
  3.13.3, fait de nouveau lire au courtier l'en-tête `x-death` posé par un client qui publie, et le
  verse dans l'historique des morts du message (correctifs des tickets #10709 et #11331 ; 3.13.0 à
  3.13.2 l'ignoraient) ;
- la PR #11608, fusionnée dans la branche 3.13 et présente dans le source de la 3.13.7, corrige la
  conversion du champ `time` venu du client ;
- le source de la v3.13.7 (`deps/rabbit/src/mc_amqpl.erl`) le confirme : `init/2`,
  `essential_properties/2`, `headers_to_deaths/2` puis `recover_deaths/2` transforment l'en-tête du
  client en annotation `deaths` quand l'indicateur `message_containers_deaths_v2` est actif ; sinon
  l'en-tête passe tel quel au consommateur ;
- RabbitMQ 4.x n'interprète plus l'en-tête d'un client (rupture annoncée en 4.0), sans qu'il soit
  établi qu'il l'efface d'un message jamais passé par le rebut.

Conclusion, avec une certitude élevée pour 3.13.3 et au-delà (code source lu, PR fusionnées) : qui peut
publier peut forger `x-death`, `time`, `queue` et `count` compris. Rien dans la plateforme ne lit
`x-death` ; le recoverer le retire même des copies.

**L'alternative proposée ne suffit pas.** La liste des noms d'étages se lit dans `x-death`, donc se
forge aussi. Borner seulement l'âge de la signature d'origine (environ 3 h 20 avec quatre étages)
élargirait de 5 minutes à plus de 3 heures la fenêtre de rejeu de tout message capturé, pour qui sait
publier sans le secret.

**Décision : un tampon de reprise.** Le recoverer, qui détient le secret, pose sur chaque copie une
étape (`x-ef-retry-queue`, `x-ef-retry-attempt`, `x-ef-retried-at`, `x-ef-original-exchange`,
`x-ef-original-routing-key`) et un tampon `x-ef-retry-stamp` : HMAC-SHA256, sous une clé dérivée du
secret des événements (jamais confondu avec une signature d'événement), de l'étape, de la signature
de l'événement, de son instant et de son corps, champs préfixés par leur longueur. À la livraison :

| Livraison | Signature HMAC | Fraîcheur |
|---|---|---|
| Première, ou tout message hors du chemin de retour | contre la route reçue | ±5 min autour de `x-ef-signed-at` |
| Retour d'un étage : échange par défaut, clé égale à la file consommatrice, étape nommant cette file, file adhérente | contre la route d'origine de l'étape, et tampon valide | au plus délai de l'étage + 10 min après `x-ef-retried-at` (2 h 10 au dernier étage), ±5 min de décalage d'horloge |

Le dédoublonnage par `event_id` reste celui du consommateur, à chaque livraison. En mode souple, tout
refus devient `ACCEPT_UNVERIFIED`, comme pour une première livraison. Une étape sans tampon, un tampon
qui ne couvre pas l'étape lue, une signature qui ne correspond pas à la route d'origine : `REJECT_INVALID`.

## Conception de `make dlq-replay`

**Contraintes.** Un message au rebut est vieux : renvoyé tel quel, il serait refusé (`REJECT_STALE` en
première livraison, tampon périmé au retour d'un étage) et retournerait au rebut. L'outil
d'exploitation ne détient pas le secret des événements et ne doit pas le détenir. Le rejeu n'ouvre
aucune porte à qui sait publier sans ce secret.

**Principe.** Le rejeu est une reprise déclenchée à la main. Le module consommateur, seul détenteur du
secret, relit sa file de rebut et réadmet chaque message au premier étage de sa topologie, sous un
tampon neuf. Une minute plus tard, le message revient par le chemin vérifié ci-dessus, sans règle
nouvelle dans le vérificateur.

**Déroulé.**

1. `make dlq-replay QUEUE=<file>.dlq [MAX=n]` (dépôt deploy) trouve le module propriétaire de la
   file et appelle, sur son port de gestion non publié, une opération d'actionneur en écriture. Aucun
   secret ne transite.
2. Le module n'accepte que la file de rebut d'une file dotée d'une topologie de reprise.
3. Il lit au plus `n` messages, borné par la profondeur au départ, un par un avec accusé manuel, pour
   ne jamais relire ce qu'il vient de remettre.
4. Route d'origine : celle de l'étape portée par le message si son tampon est valide, fraîcheur
   ignorée ; sinon celle de l'entrée `x-death` de la file consommatrice (raison `rejected`). Cette
   entrée est falsifiable : la signature de l'événement doit correspondre à la route, et la route doit
   être l'une de celles que la file consommatrice lie (clés exactes, règle 27).
5. Signature valide : copie publiée, confirmée et `mandatory`, dans `<file>.retry.1m`, étape 1,
   instant du rejeu, tampon neuf, puis accusé du message de rebut. Signature absente, invalide ou de
   version inconnue : jamais réadmis, recopié en fin de file de rebut puis accusé.
6. Un événement par issue (`replayed`, `kept`) : son écouteur écrit la ligne de journal et le compteur.

**Pourquoi c'est sûr.** Sans le secret, ni signature ni tampon : un message forgé déposé au rebut
n'est jamais réadmis. Un événement authentique capturé puis redéposé n'est réadmis que pour la file
qui lie sa route, ce qui revient à une relivraison : le dédoublonnage par `event_id` la neutralise, et
la garde sur `occurred_at_epoch_ms` tient l'ordre des statuts (§3.11). Le tampon neuf ne vaut que pour
cette copie et cet étage, 11 minutes au plus. Le geste est volontaire, déclenché par l'alerte de
`ecclesiaflow_domain_events_dlq_depth` une fois la cause corrigée ; il réadmet aussi les messages
refusés en `REJECT_STALE` pendant un arrêt du consommateur de plus de 5 minutes.

**Limite.** Une file sans topologie de reprise n'a pas de rejeu sûr : on la déclare d'abord.
