package com.ecclesiaflow.platform.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityMaskingUtilsTest {

    @Nested
    @DisplayName("maskEmail")
    class MaskEmail {
        @Test
        @DisplayName("keeps the first and last letter of the local part and only the TLD of the domain")
        void masksLocalPartAndDomain() {
            assertThat(SecurityMaskingUtils.maskEmail("alice@church.com")).isEqualTo("a***e@***.com");
        }

        @Test
        @DisplayName("a family domain names a person: nothing of it survives but the TLD")
        void masksAFamilyDomain() {
            assertThat(SecurityMaskingUtils.maskEmail("jean@dupont-famille.fr"))
                    .isEqualTo("j***n@***.fr")
                    .doesNotContain("dupont");
        }

        @Test
        @DisplayName("subdomains are masked with the rest of the domain")
        void masksSubdomains() {
            assertThat(SecurityMaskingUtils.maskEmail("alice@mail.eglise-saint-paul.org"))
                    .isEqualTo("a***e@***.org");
        }

        @Test
        @DisplayName("a local part of one or two characters keeps only its first one")
        void masksShortLocalPart() {
            assertThat(SecurityMaskingUtils.maskEmail("ab@x.com")).isEqualTo("a***@***.com");
            assertThat(SecurityMaskingUtils.maskEmail("a@x.com")).isEqualTo("a***@***.com");
        }

        @Test
        @DisplayName("a domain without a letters-only TLD is masked whole")
        void masksADomainWithoutTld() {
            assertThat(SecurityMaskingUtils.maskEmail("alice@localhost")).isEqualTo("a***e@***");
            assertThat(SecurityMaskingUtils.maskEmail("alice@[10.0.0.7]")).isEqualTo("a***e@***");
            assertThat(SecurityMaskingUtils.maskEmail("alice@")).isEqualTo("a***e@***");
        }

        @Test
        @DisplayName("a character outside the BMP is kept whole, never split in half")
        void keepsSupplementaryCharactersWhole() {
            assertThat(SecurityMaskingUtils.maskEmail("\uD835\uDC00lice\uD835\uDC01@x.com"))
                    .isEqualTo("\uD835\uDC00***\uD835\uDC01@***.com");
        }

        @Test
        void returnsUnknownOnNullOrBlank() {
            assertThat(SecurityMaskingUtils.maskEmail(null)).isEqualTo("[UNKNOWN]");
            assertThat(SecurityMaskingUtils.maskEmail("")).isEqualTo("[UNKNOWN]");
            assertThat(SecurityMaskingUtils.maskEmail("   ")).isEqualTo("[UNKNOWN]");
        }

        @Test
        void returnsInvalidWhenNoAt() {
            assertThat(SecurityMaskingUtils.maskEmail("noatsign")).isEqualTo("[INVALID_FORMAT]");
        }

        @Test
        void returnsInvalidWhenAtIsFirstChar() {
            assertThat(SecurityMaskingUtils.maskEmail("@only.com")).isEqualTo("[INVALID_FORMAT]");
        }
    }

    @Nested
    @DisplayName("maskUrlQueryParam")
    class MaskUrlQueryParam {
        @Test
        void masksTargetParamAndRedactsOthers() {
            assertThat(SecurityMaskingUtils.maskUrlQueryParam("https://x.com/api?token=abc&id=42", "token"))
                    .isEqualTo("https://x.com/api?token=****&id=[REDACTED]");
        }

        @Test
        void handlesUrlWithoutQueryString() {
            assertThat(SecurityMaskingUtils.maskUrlQueryParam("https://x.com/api", "token")).isEqualTo("[URL]");
        }

        @Test
        void handlesParamWithoutEquals() {
            assertThat(SecurityMaskingUtils.maskUrlQueryParam("https://x.com/api?flag", "token"))
                    .isEqualTo("https://x.com/api?flag");
        }

        @Test
        void returnsUnknownOnNullOrBlankUrl() {
            assertThat(SecurityMaskingUtils.maskUrlQueryParam(null, "token")).isEqualTo("[UNKNOWN]");
            assertThat(SecurityMaskingUtils.maskUrlQueryParam("", "token")).isEqualTo("[UNKNOWN]");
        }

        @Test
        void returnsUrlPlaceholderOnBlankParamName() {
            assertThat(SecurityMaskingUtils.maskUrlQueryParam("https://x.com/api?token=abc", null))
                    .isEqualTo("[URL]");
        }

        @Test
        void maskConfirmationLinkDelegatesToMaskUrlQueryParam() {
            assertThat(SecurityMaskingUtils.maskConfirmationLink("https://x.com/confirm?token=abc&u=42"))
                    .isEqualTo("https://x.com/confirm?token=****&u=[REDACTED]");
        }
    }

    @Nested
    @DisplayName("maskId")
    class MaskId {
        @Test
        void masksUuid() {
            String id = UUID.randomUUID().toString();
            String masked = SecurityMaskingUtils.maskId(id);
            assertThat(masked).startsWith(id.substring(0, 8)).endsWith("********");
        }

        @Test
        void masksShortIdAsStars() {
            assertThat(SecurityMaskingUtils.maskId("abc")).isEqualTo("********");
        }

        @Test
        void masksLongNonUuidIdKeepingFirstEight() {
            assertThat(SecurityMaskingUtils.maskId("1234567890")).isEqualTo("12345678********");
        }

        @Test
        void returnsUnknownOnNullOrBlank() {
            assertThat(SecurityMaskingUtils.maskId(null)).isEqualTo("[UNKNOWN]");
            assertThat(SecurityMaskingUtils.maskId(" ")).isEqualTo("[UNKNOWN]");
        }
    }

    @Nested
    @DisplayName("maskAny / maskArgs")
    class MaskAny {
        @Test
        void detectsEmail() {
            assertThat(SecurityMaskingUtils.maskAny("alice@church.com")).isEqualTo("a***e@***.com");
        }

        @Test
        void redactsJwtLookingValue() {
            assertThat(SecurityMaskingUtils.maskAny("AAAA.BBBB.CCCC")).isEqualTo("[REDACTED]");
        }

        @Test
        void masksUrlWithToken() {
            assertThat(SecurityMaskingUtils.maskAny("https://x.com/api?token=secret")).contains("token=****");
        }

        @Test
        void redactsBearer() {
            assertThat(SecurityMaskingUtils.maskAny("Bearer abcdef")).isEqualTo("Bearer ****");
        }

        @Test
        @DisplayName("an unclassified string is redacted, whatever its length")
        void redactsUnclassifiedString() {
            assertThat(SecurityMaskingUtils.maskAny("a".repeat(150))).isEqualTo("[REDACTED]");
            assertThat(SecurityMaskingUtils.maskAny("short")).isEqualTo("[REDACTED]");
        }

        @Test
        @DisplayName("a password never reaches the log")
        void redactsPassword() {
            assertThat(SecurityMaskingUtils.maskAny("correct-horse-battery-staple")).isEqualTo("[REDACTED]");
        }

        @Test
        @DisplayName("a person's name never reaches the log")
        void redactsName() {
            assertThat(SecurityMaskingUtils.maskAny("Jean Dupont")).isEqualTo("[REDACTED]");
        }

        @Test
        @DisplayName("a phone number is masked down to its last two digits")
        void masksPhone() {
            assertThat(SecurityMaskingUtils.maskAny("+33 6 12 34 56 78")).isEqualTo("+****78");
            assertThat(SecurityMaskingUtils.maskAny("0612345678")).isEqualTo("****78");
        }

        @Test
        @DisplayName("a UUID is masked like an id, as a string or as a UUID")
        void masksUuid() {
            UUID id = UUID.fromString("3f2b1c4d-0000-4000-8000-000000000001");
            assertThat(SecurityMaskingUtils.maskAny(id)).isEqualTo("3f2b1c4d********");
            assertThat(SecurityMaskingUtils.maskAny(id.toString())).isEqualTo("3f2b1c4d********");
        }

        @Test
        @DisplayName("numbers, booleans and enum constants are kept: they carry no personal data")
        void keepsWhitelistedTypes() {
            assertThat(SecurityMaskingUtils.maskAny(42)).isEqualTo("42");
            assertThat(SecurityMaskingUtils.maskAny(7L)).isEqualTo("7");
            assertThat(SecurityMaskingUtils.maskAny(true)).isEqualTo("true");
            assertThat(SecurityMaskingUtils.maskAny(java.time.DayOfWeek.MONDAY)).isEqualTo("MONDAY");
        }

        @Test
        @DisplayName("any other object shows its type, never its toString")
        void showsOnlyTheTypeOfOtherObjects() {
            record Credentials(String email, String password) { }

            String masked = SecurityMaskingUtils.maskAny(new Credentials("alice@church.com", "s3cret"));

            assertThat(masked).isEqualTo("[Credentials]");
        }

        @Test
        @DisplayName("an anonymous object has no type name to show")
        void labelsAnAnonymousObject() {
            Object anonymous = new Object() {
                @Override
                public String toString() {
                    return "alice@church.com";
                }
            };

            assertThat(SecurityMaskingUtils.maskAny(anonymous)).isEqualTo("[Object]");
        }

        @Test
        @DisplayName("maskArgs lets no password, phone or name through")
        void maskArgsLeaksNothing() {
            Object[] args = { "s3cr3t-Passw0rd", "+33612345678", "Jean Dupont", 7 };

            String result = SecurityMaskingUtils.maskArgs(args);

            assertThat(result)
                    .doesNotContain("s3cr3t").doesNotContain("33612345678").doesNotContain("Dupont")
                    .contains("7");
        }

        @Test
        void returnsUnknownOnNullOrBlank() {
            assertThat(SecurityMaskingUtils.maskAny(null)).isEqualTo("[UNKNOWN]");
            assertThat(SecurityMaskingUtils.maskAny("   ")).isEqualTo("[UNKNOWN]");
        }

        @Test
        void maskArgsHandlesNullAndDispatches() {
            assertThat(SecurityMaskingUtils.maskArgs(null)).isEqualTo("[]");
            Object[] args = { "alice@x.com", "Bearer abc", null };
            String result = SecurityMaskingUtils.maskArgs(args);
            assertThat(result).contains("a***e@***.com").contains("Bearer ****").contains("[UNKNOWN]");
        }

        @Test
        void returnsUrlPlaceholderForNonTokenUrl() {
            assertThat(SecurityMaskingUtils.maskAny("http://x.com/api")).isEqualTo("[URL]");
        }
    }

    @Nested
    @DisplayName("sanitizeInfra / rootMessage")
    class Sanitize {
        @Test
        void stripsUrlsHostsPorts() {
            String msg = "Connect failed to https://example.com/api host db.example.com:5432";
            assertThat(SecurityMaskingUtils.sanitizeInfra(msg)).contains("[URL]").contains("[HOST:PORT]");
        }

        @Test
        void passesThroughNullOrBlank() {
            assertThat(SecurityMaskingUtils.sanitizeInfra(null)).isNull();
            assertThat(SecurityMaskingUtils.sanitizeInfra("")).isEmpty();
        }

        @Test
        void rootMessageUnwrapsCauses() {
            Throwable root = new IllegalStateException("Boom at https://x.com");
            Throwable wrapper = new RuntimeException("wrap", root);
            assertThat(SecurityMaskingUtils.rootMessage(wrapper)).contains("[URL]");
        }

        @Test
        void rootMessageHandlesNull() {
            assertThat(SecurityMaskingUtils.rootMessage(null)).isEqualTo("[NO_ERROR]");
        }

        @Test
        void rootMessageFallsBackToClassName() {
            assertThat(SecurityMaskingUtils.rootMessage(new IllegalStateException()))
                    .isEqualTo("IllegalStateException");
        }

        @Test
        @DisplayName("F053: a long message costs milliseconds, not minutes")
        void aLongMessageIsBoundedBeforeThePatternsRun() {
            // The host patterns backtrack quadratically on long [a-zA-Z0-9._-] runs (64 KB took 41 s of CPU),
            // and anyone who can lengthen a driver's message would hold a thread for minutes.
            String hostile = "a".repeat(64 * 1024);

            long start = System.nanoTime();
            String masked = SecurityMaskingUtils.sanitizeInfra(hostile);
            long millis = (System.nanoTime() - start) / 1_000_000;

            assertThat(millis).as("64 KB took %d ms", millis).isLessThan(250L);
            // Bounded, and visibly so.
            assertThat(masked).hasSizeLessThanOrEqualTo(512 + 3).endsWith("...");
        }

        @Test
        @DisplayName("F053: the truncation does not cost the masking")
        void theMaskingStillHappensWithinTheBound() {
            String msg = "Connection to db.internal:5432 refused, see https://x.example/y";

            String masked = SecurityMaskingUtils.sanitizeInfra(msg);

            assertThat(masked).contains("[URL]").contains("[HOST:PORT]")
                    .doesNotContain("db.internal").doesNotContain("x.example");
        }

        @Test
        @DisplayName("F053: a bare domain is still masked — the pattern was NOT rewritten")
        void aBareDomainIsStillMasked() {
            // A possessive host pattern stops matching api.example.com (the middle group swallows the last
            // label), and a masker that quietly stops masking is worse: only the length is bounded.
            assertThat(SecurityMaskingUtils.sanitizeInfra("DNS lookup failed for api.example.com"))
                    .isEqualTo("DNS lookup failed for [HOST]");
        }

    }

    @Nested
    @DisplayName("sanitizeInfra - personal data inside exception messages")
    class SanitizePersonalData {

        @Test
        @DisplayName("a Postgres unique violation does not leak the email")
        void postgresDuplicateKey() {
            String msg = "ERROR: duplicate key value violates unique constraint \"uk_member_email\"\n"
                    + "  Detail: Key (email)=(alicemartin@church.com) already exists.";

            assertThat(SecurityMaskingUtils.sanitizeInfra(msg))
                    .doesNotContain("alicemartin")
                    .contains("uk_member_email").contains("already exists");
        }

        @Test
        @DisplayName("an email inside a message gets the same mask as maskEmail, domain included")
        void emailInTextIsMaskedLikeMaskEmail() {
            String msg = "Detail: Key (email)=(alicemartin@famille-martin.fr) already exists.";

            assertThat(SecurityMaskingUtils.sanitizeInfra(msg))
                    .isEqualTo("Detail: Key (email)=(a***n@***.fr) already exists.");
        }

        @Test
        @DisplayName("quotes and punctuation around an email stay outside its mask")
        void punctuationAroundAnEmailIsKept() {
            assertThat(SecurityMaskingUtils.sanitizeInfra("Email 'bobleroy@famille-leroy.fr' is already registered"))
                    .isEqualTo("Email 'b***y@***.fr' is already registered");
            assertThat(SecurityMaskingUtils.sanitizeInfra("550 5.1.1 <jdupont@gmail.com>: rejected"))
                    .isEqualTo("550 5.1.1 <j***t@***.com>: rejected");
            assertThat(SecurityMaskingUtils.sanitizeInfra("Write to alice@church.com."))
                    .isEqualTo("Write to a***e@***.com.");
        }

        @Test
        @DisplayName("a letter outside the BMP at the edge of an address is part of it, not punctuation")
        void supplementaryLetterAtTheEdge() {
            assertThat(SecurityMaskingUtils.sanitizeInfra("from 𝐀lice@church.com"))
                    .isEqualTo("from 𝐀***e@***.com");
        }

        @Test
        @DisplayName("an address with no local part still loses its domain")
        void noLocalPart() {
            assertThat(SecurityMaskingUtils.sanitizeInfra("Rejected '@famille-leroy.fr'"))
                    .isEqualTo("Rejected '[INVALID_FORMAT]'");
        }

        @Test
        @DisplayName("an address whose domain is only punctuation keeps the punctuation outside the mask")
        void punctuationOnlyDomain() {
            assertThat(SecurityMaskingUtils.sanitizeInfra("Unknown recipient alice@..."))
                    .isEqualTo("Unknown recipient a***e@***...");
        }

        @Test
        @DisplayName("credentials written as user:password@host lose both the password and the host")
        void credentialsBeforeAHost() {
            assertThat(SecurityMaskingUtils.sanitizeInfra("auth failed for default:hunter2@cache.internal:6379"))
                    .doesNotContain("hunter2").doesNotContain("cache").doesNotContain("6379");
        }

        @Test
        @DisplayName("an SMTP rejection does not leak the recipient")
        void smtpRecipientRejected() {
            String msg = "550 5.1.1 <jdupont@gmail.com>: Recipient address rejected: User unknown";

            assertThat(SecurityMaskingUtils.sanitizeInfra(msg))
                    .doesNotContain("jdupont")
                    .contains("Recipient address rejected");
        }

        @Test
        @DisplayName("a quoted email is masked too")
        void quotedEmail() {
            assertThat(SecurityMaskingUtils.sanitizeInfra("Email 'bobleroy@example.org' is already registered"))
                    .doesNotContain("bobleroy")
                    .contains("is already registered");
        }

        @Test
        @DisplayName("an E.164 phone number is masked")
        void e164Phone() {
            assertThat(SecurityMaskingUtils.sanitizeInfra("The 'To' number +33612345678 is not a valid phone number."))
                    .doesNotContain("33612345678")
                    .contains("+****78");
        }

        @Test
        @DisplayName("a bearer token is masked")
        void bearerToken() {
            assertThat(SecurityMaskingUtils.sanitizeInfra("Rejected header Authorization: Bearer abc123.def-456_ghi"))
                    .doesNotContain("abc123").doesNotContain("ghi")
                    .contains("Bearer ****");
        }

        @Test
        @DisplayName("a JWT is redacted")
        void jwt() {
            String msg = "JWT expired: eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjM0In0.c2ln-bmF0_dXJl1";

            assertThat(SecurityMaskingUtils.sanitizeInfra(msg))
                    .doesNotContain("eyJ").doesNotContain("c2ln")
                    .contains("[REDACTED]");
        }
    }

    @Nested
    @DisplayName("sanitizeInfra - infrastructure addresses")
    class SanitizeInfrastructure {

        @Test
        @DisplayName("an unresolved socket address hides the host name")
        void unresolvedSocketAddress() {
            assertThat(SecurityMaskingUtils.sanitizeInfra("Unable to connect to redis/<unresolved>:6379"))
                    .isEqualTo("Unable to connect to [HOST:PORT]");
        }

        @Test
        @DisplayName("a resolved socket address hides both the name and the IP")
        void resolvedSocketAddress() {
            assertThat(SecurityMaskingUtils.sanitizeInfra("Connection refused: keycloak/172.18.0.3:8080"))
                    .isEqualTo("Connection refused: [HOST:PORT]");
        }

        @Test
        @DisplayName("a quoted URL is removed without its closing quote")
        void quotedUrl() {
            assertThat(SecurityMaskingUtils.sanitizeInfra("I/O error on GET request for \"http://kc:8080/certs\": refused"))
                    .isEqualTo("I/O error on GET request for \"[URL]\": refused");
        }

        @Test
        @DisplayName("a non-HTTP URI with credentials is removed whole")
        void credentialUri() {
            assertThat(SecurityMaskingUtils.sanitizeInfra("Cannot connect to redis://default:hunter2@cache.internal:6379/0"))
                    .doesNotContain("hunter2").doesNotContain("cache.internal")
                    .isEqualTo("Cannot connect to [URL]");
        }
    }

    @Nested
    @DisplayName("maskPhone")
    class MaskPhone {

        @Test
        @DisplayName("keeps the leading plus and the last two digits")
        void keepsPlusAndLastTwoDigits() {
            assertThat(SecurityMaskingUtils.maskPhone("+33612345678")).isEqualTo("+****78");
            assertThat(SecurityMaskingUtils.maskPhone("+33 6 12 34 56 78")).isEqualTo("+****78");
            assertThat(SecurityMaskingUtils.maskPhone("06.12.34.56.78")).isEqualTo("****78");
        }

        @Test
        @DisplayName("a number too short to keep any digit is fully masked")
        void masksVeryShortNumber() {
            assertThat(SecurityMaskingUtils.maskPhone("+1")).isEqualTo("+****");
        }

        @Test
        @DisplayName("null, blank and digitless input")
        void handlesDegenerateInput() {
            assertThat(SecurityMaskingUtils.maskPhone(null)).isEqualTo("[UNKNOWN]");
            assertThat(SecurityMaskingUtils.maskPhone("  ")).isEqualTo("[UNKNOWN]");
            assertThat(SecurityMaskingUtils.maskPhone("n/a")).isEqualTo("[INVALID_FORMAT]");
        }
    }

    @Nested
    @DisplayName("maskBody")
    class MaskBody {

        @Test
        @DisplayName("keeps only the length of a message body")
        void keepsOnlyTheLength() {
            assertThat(SecurityMaskingUtils.maskBody("Prière pour Jean")).isEqualTo("[BODY length=16]");
        }

        @Test
        @DisplayName("null body")
        void handlesNull() {
            assertThat(SecurityMaskingUtils.maskBody(null)).isEqualTo("[UNKNOWN]");
        }
    }

    @Nested
    @DisplayName("escapeControlChars")
    class EscapeControlChars {

        @Test
        @DisplayName("CR, LF and tab are written as their escapes: no new line can start")
        void escapesLineBreaksAndTab() {
            assertThat(SecurityMaskingUtils.escapeControlChars("auth.v1\r\nINFO forged\tline"))
                    .isEqualTo("auth.v1\\r\\nINFO forged\\tline");
        }

        @Test
        @DisplayName("other controls, such as an ANSI escape or NEL, become \\uXXXX")
        void escapesOtherControls() {
            assertThat(SecurityMaskingUtils.escapeControlChars("a\u001b[2Kb\u0085c\u0000"))
                    .isEqualTo("a\\u001B[2Kb\\u0085c\\u0000");
        }

        @Test
        @DisplayName("Unicode line and paragraph separators are escaped")
        void escapesUnicodeSeparators() {
            assertThat(SecurityMaskingUtils.escapeControlChars("a\u2028b\u2029c"))
                    .isEqualTo("a\\u2028b\\u2029c");
        }

        @Test
        @DisplayName("invisible format characters that disguise a line are escaped, outside the BMP too")
        void escapesFormatCharacters() {
            assertThat(SecurityMaskingUtils.escapeControlChars("ok\u202Edetaerc"))
                    .isEqualTo("ok\\u202Edetaerc");
            assertThat(SecurityMaskingUtils.escapeControlChars("x" + new String(Character.toChars(0xE0041))))
                    .isEqualTo("x\\uDB40\\uDC41");
        }

        @Test
        @DisplayName("a backslash is doubled, so a literal \\n never reads as an escaped line break")
        void escapesBackslash() {
            assertThat(SecurityMaskingUtils.escapeControlChars("auth.v1\\nINFO forged"))
                    .isEqualTo("auth.v1\\\\nINFO forged");
            assertThat(SecurityMaskingUtils.escapeControlChars("a\\\nb"))
                    .as("a real line break after a backslash stays distinguishable")
                    .isEqualTo("a\\\\\\nb");
        }

        @Test
        @DisplayName("printable text, accents and emoji included, is returned as is")
        void keepsPrintableText() {
            String plain = "église.membre-créé.v1 \uD83D\uDE4F";

            assertThat(SecurityMaskingUtils.escapeControlChars(plain)).isSameAs(plain);
        }

        @Test
        @DisplayName("null stays null")
        void passesNullThrough() {
            assertThat(SecurityMaskingUtils.escapeControlChars(null)).isNull();
        }
    }

    @Nested
    @DisplayName("abbreviate")
    class Abbreviate {
        @Test
        void truncatesLongStrings() {
            assertThat(SecurityMaskingUtils.abbreviate("hello world", 5)).isEqualTo("hello...");
        }

        @Test
        void keepsShortStrings() {
            assertThat(SecurityMaskingUtils.abbreviate("hi", 10)).isEqualTo("hi");
        }

        @Test
        void handlesNull() {
            assertThat(SecurityMaskingUtils.abbreviate(null, 10)).isEqualTo("[UNKNOWN]");
        }
    }
}
