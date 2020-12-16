// camel-k: language=java

import org.apache.camel.BindToRegistry;
import org.apache.camel.builder.RouteBuilder;

import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;

public class GitHubApp extends RouteBuilder {
    
    static final String CLIENT_ID       = "client_id";
    static final String CLIENT_SECRET   = "client_secret";
    static final String WEBHOOK_SECRET  = "webhook_secret";
    static final String PEM             = "pem";

    @BindToRegistry("kubernetesClient")
    KubernetesClient kubernetesClient = new DefaultKubernetesClient();
    
    @Override
    public void configure() throws Exception {

        rest()
            .get("/callback").to("direct:callback")
            .post("/events").to("direct:events");
    }
    
    public static void main(String[] args) {
        // TODO
    }
}
