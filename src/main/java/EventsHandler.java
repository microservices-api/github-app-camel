// camel-k: language=java

import java.util.Base64;
import java.util.Base64.Decoder;
import java.util.Map;

import org.apache.camel.ExchangeProperty;
import org.apache.camel.Header;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kubernetes.KubernetesConstants;
import org.apache.camel.support.builder.PredicateBuilder;

import io.fabric8.kubernetes.api.model.Secret;

public class EventsHandler extends RouteBuilder {

    private static final String X_SIGNATURE = "X-Hub-Signature";
    private static final String X_EVENT     = "X-GitHub-Event";

    void validatePayload(
        @Header(X_SIGNATURE) String signature,
        @ExchangeProperty(GitHubApp.WEBHOOK_SECRET) String secret,
        @ExchangeProperty("payload") byte[] payload) {

        log.info("Signature: {}", signature);
        log.info("Secret: {}", secret);
        log.info("Payload: {}", payload);
    }

    @Override
    public void configure() throws Exception {

        from("direct:events")

            .log("Received event:")
            .log("-> ${headers[X-Github-Event]}")
            .log("-> ${body}")

            // save payload for validation later
            .setProperty("payload", body())

            // unmarshal to JSON object
            .unmarshal().json()

            .choice()
                .when(PredicateBuilder.and(
                    header(X_EVENT).isEqualTo("installation"),
                    simple("${body[action]} == 'created'")))

                        .to("direct:get-secret")
                        .bean(this, "validatePayload")
            .end()
            .setBody(simple("${null}"));

        from("direct:get-secret")

            // save original body
            .setProperty("original", body())

            .setHeader(KubernetesConstants.KUBERNETES_NAMESPACE_NAME, simple("${env:NAMESPACE}"))
            .setHeader(KubernetesConstants.KUBERNETES_SECRET_NAME, simple("github-app-${body[installation][app_id]}"))

            .to("kubernetes-secrets:///?kubernetesClient=#kubernetesClient&operation=getSecret")
            .process(exchange -> {

                Secret secret = exchange.getIn().getBody(Secret.class);
                if (secret != null) {

                    Decoder decoder = Base64.getDecoder();
                    Map<String, String> data = secret.getData();
                    
                    exchange.setProperty(GitHubApp.WEBHOOK_SECRET, decoder.decode(data.get(GitHubApp.WEBHOOK_SECRET)));
                }
            })

            // restore original body
            .setBody(exchangeProperty("original"));
    }
}
