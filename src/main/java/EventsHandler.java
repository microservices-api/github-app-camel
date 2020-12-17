// camel-k: language=java

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SignatureException;
import java.util.Base64;
import java.util.Base64.Decoder;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.camel.Exchange;
import org.apache.camel.ExchangeProperty;
import org.apache.camel.Header;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kubernetes.KubernetesConstants;
import org.apache.camel.support.builder.PredicateBuilder;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;

import io.fabric8.kubernetes.api.model.Secret;

public class EventsHandler extends RouteBuilder {

    private static final String X_SIGNATURE = "X-Hub-Signature-256";
    private static final String X_EVENT     = "X-GitHub-Event";

    private static final String HMAC_SHA256 = "HmacSHA256";

    void validateSignature(
        @Header(X_SIGNATURE) String signature,
        @ExchangeProperty(GitHubApp.WEBHOOK_SECRET) byte[] secret,
        @ExchangeProperty("payload") byte[] payload)
        throws DecoderException, GeneralSecurityException {
        
        // hex decode signature (minus sha256= prefix) to byte[]
        signature = signature.substring(7);
        byte[] actualSig = Hex.decodeHex(signature.toCharArray());

        // compute the expected signature
        Mac hmac = Mac.getInstance(HMAC_SHA256);
        hmac.init(new SecretKeySpec(secret, HMAC_SHA256));
        byte[] expectedSig = hmac.doFinal(payload);

        if (!MessageDigest.isEqual(actualSig, expectedSig))
            throw new SignatureException("Signature validation failed");

        log.info("-> Signature {} is valid", signature);
    }

    @Override
    public void configure() throws Exception {

        from("direct:events")

            .onException(Exception.class)
                .handled(true)
                .removeHeaders("*")
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(404))
                .transform(constant("Not found"))
            .end()

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
                        .bean(this, "validateSignature")
                        .log("TODO")
            .end()
            .removeHeaders("*")
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
