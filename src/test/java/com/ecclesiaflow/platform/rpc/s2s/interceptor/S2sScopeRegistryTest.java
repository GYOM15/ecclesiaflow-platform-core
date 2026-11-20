package com.ecclesiaflow.platform.rpc.s2s.interceptor;

import com.ecclesiaflow.grpc.billing.BillingCreditServiceGrpc;
import com.ecclesiaflow.grpc.billing.BillingEntitlementServiceGrpc;
import com.ecclesiaflow.grpc.billing.CommitCreditsRequest;
import com.ecclesiaflow.grpc.billing.CommitCreditsResponse;
import com.ecclesiaflow.grpc.billing.GetCreditBalanceRequest;
import com.ecclesiaflow.grpc.billing.GetCreditBalanceResponse;
import com.ecclesiaflow.grpc.billing.GetEntitlementRequest;
import com.ecclesiaflow.grpc.billing.GetEntitlementResponse;
import com.ecclesiaflow.grpc.billing.ReleaseCreditsRequest;
import com.ecclesiaflow.grpc.billing.ReleaseCreditsResponse;
import com.ecclesiaflow.grpc.billing.ReserveCreditsRequest;
import com.ecclesiaflow.grpc.billing.ReserveCreditsResponse;
import com.ecclesiaflow.grpc.church.CanModerateGroupContentRequest;
import com.ecclesiaflow.grpc.church.CanModerateGroupContentResponse;
import com.ecclesiaflow.grpc.church.CheckAccountClosureRequest;
import com.ecclesiaflow.grpc.church.CheckAccountClosureResponse;
import com.ecclesiaflow.grpc.church.ChurchServiceGrpc;
import com.ecclesiaflow.grpc.church.OnSignupCompletedRequest;
import com.ecclesiaflow.grpc.church.OnSignupCompletedResponse;
import com.ecclesiaflow.grpc.church.RepublishActiveMembershipsRequest;
import com.ecclesiaflow.grpc.church.RepublishActiveMembershipsResponse;
import com.ecclesiaflow.grpc.payments.ChargeSavedMethodRequest;
import com.ecclesiaflow.grpc.payments.ChargeSavedMethodResponse;
import com.ecclesiaflow.grpc.payments.CreateOnboardingSessionRequest;
import com.ecclesiaflow.grpc.payments.CreateOnboardingSessionResponse;
import com.ecclesiaflow.grpc.payments.DisableMerchantAccountRequest;
import com.ecclesiaflow.grpc.payments.DisableMerchantAccountResponse;
import com.ecclesiaflow.grpc.payments.ErasePayerRequest;
import com.ecclesiaflow.grpc.payments.ErasePayerResponse;
import com.ecclesiaflow.grpc.payments.FindPaymentByReferenceRequest;
import com.ecclesiaflow.grpc.payments.FindPaymentByReferenceResponse;
import com.ecclesiaflow.grpc.payments.GetMerchantAccountRequest;
import com.ecclesiaflow.grpc.payments.GetMerchantAccountResponse;
import com.ecclesiaflow.grpc.payments.GetPaymentRequest;
import com.ecclesiaflow.grpc.payments.GetPaymentResponse;
import com.ecclesiaflow.grpc.payments.GetProviderStatusRequest;
import com.ecclesiaflow.grpc.payments.GetProviderStatusResponse;
import com.ecclesiaflow.grpc.payments.ListPaymentsRequest;
import com.ecclesiaflow.grpc.payments.ListPaymentsResponse;
import com.ecclesiaflow.grpc.payments.ListPayoutLinesRequest;
import com.ecclesiaflow.grpc.payments.ListPayoutLinesResponse;
import com.ecclesiaflow.grpc.payments.OpenMerchantAccountRequest;
import com.ecclesiaflow.grpc.payments.OpenMerchantAccountResponse;
import com.ecclesiaflow.grpc.payments.PaymentsServiceGrpc;
import com.ecclesiaflow.grpc.payments.RefundPaymentRequest;
import com.ecclesiaflow.grpc.payments.RefundPaymentResponse;
import com.ecclesiaflow.grpc.payments.StartMethodSetupRequest;
import com.ecclesiaflow.grpc.payments.StartMethodSetupResponse;
import com.ecclesiaflow.grpc.payments.StartPaymentRequest;
import com.ecclesiaflow.grpc.payments.StartPaymentResponse;
import io.grpc.BindableService;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.StreamObserver;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class S2sScopeRegistryTest {

    private static final String TEST_SERVICE = "ecclesiaflow.test.TestService";

    /** No services registered: every business method is unmapped (fail-closed at the interceptor). */
    private final S2sScopeRegistry registry = new S2sScopeRegistry(List.of());

    @Test
    void noServicesMeansEmptyRegistry() {
        assertThat(registry.size()).isZero();
        assertThat(registry.requiredScope("ecclesiaflow.members.MembersService/NotifyAccountActivated"))
                .isEqualTo(Optional.empty());
    }

    @Test
    void exemptsHealthService() {
        assertThat(registry.isInfrastructureService("grpc.health.v1.Health/Check")).isTrue();
        assertThat(registry.isInfrastructureService("grpc.health.v1.Health/Watch")).isTrue();
    }

    @Test
    void exemptsReflectionServices() {
        assertThat(registry.isInfrastructureService(
                "grpc.reflection.v1.ServerReflection/ServerReflectionInfo")).isTrue();
        assertThat(registry.isInfrastructureService(
                "grpc.reflection.v1alpha.ServerReflection/ServerReflectionInfo")).isTrue();
    }

    @Test
    void doesNotExemptBusinessService() {
        assertThat(registry.isInfrastructureService(
                "ecclesiaflow.members.MembersService/NotifyAccountActivated")).isFalse();
    }

    @Test
    void handlesNullAndUnslashedNames() {
        assertThat(registry.isInfrastructureService(null)).isFalse();
        // A bare service name (no '/RpcName') is matched on the whole string.
        assertThat(registry.isInfrastructureService("grpc.health.v1.Health")).isTrue();
        assertThat(registry.isInfrastructureService("some.random.Service")).isFalse();
    }

    @Test
    void indexesAnnotatedMethodOnPlainBean() {
        S2sScopeRegistry reg = new S2sScopeRegistry(List.of(new StubGrpcService()));
        assertThat(reg.requiredScope(TEST_SERVICE + "/DoThing")).contains("ef:test:do");
        assertThat(reg.size()).isEqualTo(1);
    }

    /**
     * gRPC beans are CGLIB-proxied by the logging aspects and the proxy subclass does not carry the method
     * annotations, so the registry must scan the target class.
     */
    @Test
    void indexesAnnotatedMethodThroughCglibProxy() {
        ProxyFactory pf = new ProxyFactory(new StubGrpcService());
        pf.setProxyTargetClass(true); // force a CGLIB subclass proxy, like Spring's AOP
        pf.addAdvice((MethodInterceptor) MethodInvocation::proceed);
        BindableService proxied = (BindableService) pf.getProxy();

        S2sScopeRegistry reg = new S2sScopeRegistry(List.of(proxied));
        assertThat(reg.requiredScope(TEST_SERVICE + "/DoThing"))
                .as("the @S2sScopeRequired annotation must be seen through the CGLIB proxy")
                .contains("ef:test:do");
    }

    @Test
    void refusesToStartWhenAnRpcDeclaresNoScope() {
        // Without this check the gap only shows on the first call, as a PERMISSION_DENIED
        // in production.
        assertThatThrownBy(() -> new S2sScopeRegistry(List.of(new PartiallyAnnotatedService())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(TEST_SERVICE + "/DoOther")
                .hasMessageNotContaining(TEST_SERVICE + "/DoThing");
    }

    @Test
    void refusesToStartWhenAnAnnotationMatchesNoRpc() {
        // A Java method whose capitalised name is not an RPC (a helper, a renamed RPC,
        // a proto name that is not UpperCamelCase) guards nothing.
        assertThatThrownBy(() -> new S2sScopeRegistry(List.of(new OrphanAnnotationService())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(TEST_SERVICE + "/DoGhost");
    }

    @Test
    void infrastructureServicesNeedNoAnnotation() {
        assertThatCode(() -> new S2sScopeRegistry(List.of(new HealthLikeService())))
                .doesNotThrowAnyException();
    }

    /** The services of the published contracts, bound as a serving module binds them. */
    @Nested
    @DisplayName("over the generated contracts")
    class GeneratedContracts {

        @Test
        @DisplayName("billing's credit RPCs are keyed by their full gRPC name, each with the scope its caller holds")
        void billingCreditService() {
            S2sScopeRegistry reg = new S2sScopeRegistry(List.of(new BillingCreditService()));

            assertThat(reg.size()).isEqualTo(4);
            assertThat(reg.requiredScope("ecclesiaflow.billing.BillingCreditService/ReserveCredits"))
                    .contains("ef:billing:credit:reserve");
            assertThat(reg.requiredScope("ecclesiaflow.billing.BillingCreditService/CommitCredits"))
                    .contains("ef:billing:credit:reserve");
            assertThat(reg.requiredScope("ecclesiaflow.billing.BillingCreditService/ReleaseCredits"))
                    .contains("ef:billing:credit:reserve");
            assertThat(reg.requiredScope("ecclesiaflow.billing.BillingCreditService/GetCreditBalance"))
                    .contains("ef:billing:credit:read");
        }

        @Test
        @DisplayName("billing's entitlement read is keyed by its full gRPC name")
        void billingEntitlementService() {
            S2sScopeRegistry reg = new S2sScopeRegistry(List.of(new BillingEntitlementService()));

            assertThat(reg.size()).isEqualTo(1);
            assertThat(reg.requiredScope("ecclesiaflow.billing.BillingEntitlementService/GetEntitlement"))
                    .contains("ef:billing:entitlement:read");
        }

        @Test
        @DisplayName("a church build that annotates only the RPCs it served before does not start")
        void churchServiceWithoutTheBillingRpcs() {
            assertThatThrownBy(() -> new S2sScopeRegistry(List.of(new ChurchServiceBeforeBilling())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ecclesiaflow.church.ChurchService/CheckBillingRight")
                    .hasMessageContaining("ecclesiaflow.church.ChurchService/GetBillingProfile")
                    .hasMessageNotContaining("ecclesiaflow.church.ChurchService/CanModerateGroupContent");
        }

        @Test
        @DisplayName("the payments RPCs are keyed by their full gRPC name, each with its own ef:payments scope")
        void paymentsService() {
            S2sScopeRegistry reg = new S2sScopeRegistry(List.of(new PaymentsService()));

            assertThat(reg.size()).isEqualTo(PAYMENTS_SCOPES.size());
            PAYMENTS_SCOPES.forEach((rpc, scope) -> assertThat(
                    reg.requiredScope("ecclesiaflow.payments.PaymentsService/" + rpc)).as(rpc).contains(scope));
        }
    }

    static class BillingCreditService extends BillingCreditServiceGrpc.BillingCreditServiceImplBase {

        @S2sScopeRequired("ef:billing:credit:reserve")
        @Override
        public void reserveCredits(ReserveCreditsRequest request, StreamObserver<ReserveCreditsResponse> response) {
        }

        @S2sScopeRequired("ef:billing:credit:reserve")
        @Override
        public void commitCredits(CommitCreditsRequest request, StreamObserver<CommitCreditsResponse> response) {
        }

        @S2sScopeRequired("ef:billing:credit:reserve")
        @Override
        public void releaseCredits(ReleaseCreditsRequest request, StreamObserver<ReleaseCreditsResponse> response) {
        }

        @S2sScopeRequired("ef:billing:credit:read")
        @Override
        public void getCreditBalance(GetCreditBalanceRequest request,
                                     StreamObserver<GetCreditBalanceResponse> response) {
        }
    }

    static class BillingEntitlementService extends BillingEntitlementServiceGrpc.BillingEntitlementServiceImplBase {

        @S2sScopeRequired("ef:billing:entitlement:read")
        @Override
        public void getEntitlement(GetEntitlementRequest request, StreamObserver<GetEntitlementResponse> response) {
        }
    }

    // Read and write are split per object, so a caller that only reconciles holds no power to charge or refund.
    static final Map<String, String> PAYMENTS_SCOPES = Map.ofEntries(
            Map.entry("OpenMerchantAccount", "ef:payments:merchant:write"),
            Map.entry("CreateOnboardingSession", "ef:payments:merchant:session"),
            Map.entry("GetMerchantAccount", "ef:payments:merchant:read"),
            Map.entry("DisableMerchantAccount", "ef:payments:merchant:write"),
            Map.entry("StartPayment", "ef:payments:payment:write"),
            Map.entry("ChargeSavedMethod", "ef:payments:payment:write"),
            Map.entry("StartMethodSetup", "ef:payments:payment:write"),
            Map.entry("GetPayment", "ef:payments:payment:read"),
            Map.entry("FindPaymentByReference", "ef:payments:payment:read"),
            Map.entry("ListPayments", "ef:payments:payment:read"),
            Map.entry("RefundPayment", "ef:payments:refund:write"),
            Map.entry("ListPayoutLines", "ef:payments:payout:read"),
            Map.entry("GetProviderStatus", "ef:payments:status:read"),
            Map.entry("ErasePayer", "ef:payments:payer:erase"));

    static class PaymentsService extends PaymentsServiceGrpc.PaymentsServiceImplBase {

        @S2sScopeRequired("ef:payments:merchant:write")
        @Override
        public void openMerchantAccount(OpenMerchantAccountRequest request,
                                        StreamObserver<OpenMerchantAccountResponse> response) {
        }

        @S2sScopeRequired("ef:payments:merchant:session")
        @Override
        public void createOnboardingSession(CreateOnboardingSessionRequest request,
                                            StreamObserver<CreateOnboardingSessionResponse> response) {
        }

        @S2sScopeRequired("ef:payments:merchant:read")
        @Override
        public void getMerchantAccount(GetMerchantAccountRequest request,
                                       StreamObserver<GetMerchantAccountResponse> response) {
        }

        @S2sScopeRequired("ef:payments:merchant:write")
        @Override
        public void disableMerchantAccount(DisableMerchantAccountRequest request,
                                           StreamObserver<DisableMerchantAccountResponse> response) {
        }

        @S2sScopeRequired("ef:payments:payment:write")
        @Override
        public void startPayment(StartPaymentRequest request, StreamObserver<StartPaymentResponse> response) {
        }

        @S2sScopeRequired("ef:payments:payment:write")
        @Override
        public void chargeSavedMethod(ChargeSavedMethodRequest request,
                                      StreamObserver<ChargeSavedMethodResponse> response) {
        }

        @S2sScopeRequired("ef:payments:payment:write")
        @Override
        public void startMethodSetup(StartMethodSetupRequest request,
                                     StreamObserver<StartMethodSetupResponse> response) {
        }

        @S2sScopeRequired("ef:payments:payment:read")
        @Override
        public void getPayment(GetPaymentRequest request, StreamObserver<GetPaymentResponse> response) {
        }

        @S2sScopeRequired("ef:payments:payment:read")
        @Override
        public void findPaymentByReference(FindPaymentByReferenceRequest request,
                                           StreamObserver<FindPaymentByReferenceResponse> response) {
        }

        @S2sScopeRequired("ef:payments:payment:read")
        @Override
        public void listPayments(ListPaymentsRequest request, StreamObserver<ListPaymentsResponse> response) {
        }

        @S2sScopeRequired("ef:payments:refund:write")
        @Override
        public void refundPayment(RefundPaymentRequest request, StreamObserver<RefundPaymentResponse> response) {
        }

        @S2sScopeRequired("ef:payments:payout:read")
        @Override
        public void listPayoutLines(ListPayoutLinesRequest request, StreamObserver<ListPayoutLinesResponse> response) {
        }

        @S2sScopeRequired("ef:payments:status:read")
        @Override
        public void getProviderStatus(GetProviderStatusRequest request,
                                      StreamObserver<GetProviderStatusResponse> response) {
        }

        @S2sScopeRequired("ef:payments:payer:erase")
        @Override
        public void erasePayer(ErasePayerRequest request, StreamObserver<ErasePayerResponse> response) {
        }
    }

    /** ChurchService as church served it before billing's RPCs were added. */
    static class ChurchServiceBeforeBilling extends ChurchServiceGrpc.ChurchServiceImplBase {

        @S2sScopeRequired("ef:church:signup:activate")
        @Override
        public void onSignupCompleted(OnSignupCompletedRequest request,
                                      StreamObserver<OnSignupCompletedResponse> response) {
        }

        @S2sScopeRequired("ef:church:membership:backfill")
        @Override
        @SuppressWarnings("deprecation")
        public void republishActiveMemberships(RepublishActiveMembershipsRequest request,
                                               StreamObserver<RepublishActiveMembershipsResponse> response) {
        }

        @S2sScopeRequired("ef:church:account-closure:read")
        @Override
        public void checkAccountClosure(CheckAccountClosureRequest request,
                                        StreamObserver<CheckAccountClosureResponse> response) {
        }

        @S2sScopeRequired("ef:church:group-moderation:read")
        @Override
        public void canModerateGroupContent(CanModerateGroupContentRequest request,
                                            StreamObserver<CanModerateGroupContentResponse> response) {
        }
    }

    private static ServerServiceDefinition definition(String service, String... rpcs) {
        ServerServiceDefinition.Builder builder = ServerServiceDefinition.builder(service);
        for (String rpc : rpcs) {
            MethodDescriptor<Object, Object> method = MethodDescriptor.newBuilder(NoopMarshaller.INSTANCE, NoopMarshaller.INSTANCE)
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName(MethodDescriptor.generateFullMethodName(service, rpc))
                    .build();
            builder.addMethod(method, (call, headers) -> new ServerCall.Listener<>() { });
        }
        return builder.build();
    }

    static class StubGrpcService implements BindableService {
        @Override
        public ServerServiceDefinition bindService() {
            return definition(TEST_SERVICE, "DoThing");
        }

        @S2sScopeRequired("ef:test:do")
        public void doThing() {
            // only the annotation matters to the registry
        }
    }

    static class PartiallyAnnotatedService implements BindableService {
        @Override
        public ServerServiceDefinition bindService() {
            return definition(TEST_SERVICE, "DoThing", "DoOther");
        }

        @S2sScopeRequired("ef:test:do")
        public void doThing() {
        }

        public void doOther() {
        }
    }

    static class OrphanAnnotationService implements BindableService {
        @Override
        public ServerServiceDefinition bindService() {
            return definition(TEST_SERVICE, "DoThing");
        }

        @S2sScopeRequired("ef:test:do")
        public void doThing() {
        }

        @S2sScopeRequired("ef:test:ghost")
        public void doGhost() {
        }
    }

    static class HealthLikeService implements BindableService {
        @Override
        public ServerServiceDefinition bindService() {
            return definition("grpc.health.v1.Health", "Check");
        }
    }

    private enum NoopMarshaller implements MethodDescriptor.Marshaller<Object> {
        INSTANCE;

        @Override
        public InputStream stream(Object value) {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public Object parse(InputStream stream) {
            return new Object();
        }
    }
}
