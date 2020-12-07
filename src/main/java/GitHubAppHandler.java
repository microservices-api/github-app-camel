// camel-k: language=java

import java.util.HashMap;
import java.util.Map;

import org.apache.camel.BindToRegistry;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kubernetes.KubernetesConstants;
import org.apache.camel.language.simple.Simple;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;

public class GitHubAppHandler extends RouteBuilder {
    
    @BindToRegistry("kubernetesClient")
    KubernetesClient kubernetesClient = new DefaultKubernetesClient();

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
        stringData.put("client_id", clientId);
        stringData.put("client_secret", clientSecret);
        stringData.put("webhook_secret", webhookSecret);
        stringData.put("pem", pem);
        secret.setStringData(stringData);

        return secret;
    }

    @Override
    public void configure() throws Exception {

        rest()
            .get("/callback").to("direct:callback")
            .post("/events").to("direct:events");

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

        from("direct:events")
            .log("Event: ${body}");
    }
    
    public static void main(String[] args) {
        // TODO
    }
}
