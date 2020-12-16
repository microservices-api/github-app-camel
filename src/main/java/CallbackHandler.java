// camel-k: language=java

import java.util.HashMap;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kubernetes.KubernetesConstants;
import org.apache.camel.language.simple.Simple;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Secret;

public class CallbackHandler extends RouteBuilder {

    Secret createSecret(
        @Simple("${body[id]}") String id,
        @Simple("${body[client_id]}") String clientId,
        @Simple("${body[client_secret]}") String clientSecret,
        @Simple("${body[webhook_secret]}") String webhookSecret, 
        @Simple("${body[pem]}") String pem) {
        
        Secret secret = new Secret();

        ObjectMeta metadata = new ObjectMeta();
        metadata.setName("github-app-" + id);
        secret.setMetadata(metadata);

        Map<String, String> stringData = new HashMap<>();
        stringData.put(GitHubApp.CLIENT_ID, clientId);
        stringData.put(GitHubApp.CLIENT_SECRET, clientSecret);
        stringData.put(GitHubApp.WEBHOOK_SECRET, webhookSecret);
        stringData.put(GitHubApp.PEM, pem);
        secret.setStringData(stringData);

        return secret;
    }

    @Override
    public void configure() throws Exception {

        from("direct:callback")

            .setProperty("code", header("code"))
            .removeHeaders("*")
            .toD("https://api.github.com/app-manifests/${exchangeProperty[code]}/conversions?httpMethod=POST")
            .unmarshal().json()
            .log("App created: ${body[id]}")

            .wireTap("direct:create-secret")

            .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(301))
            .setHeader("Location", simple("${body[html_url]}"))
            .setBody(simple("${null}"));

        from("direct:create-secret")
            .setHeader(KubernetesConstants.KUBERNETES_NAMESPACE_NAME, simple("${env:NAMESPACE}"))
            .setHeader(KubernetesConstants.KUBERNETES_SECRET, method(this, "createSecret"))
            .to("kubernetes-secrets:///?kubernetesClient=#kubernetesClient&operation=createSecret");
    }
}
