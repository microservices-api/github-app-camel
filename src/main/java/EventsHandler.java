// camel-k: language=java

import java.io.IOException;
import java.io.StringReader;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.SignatureException;
import java.security.interfaces.RSAPrivateKey;
import java.util.Base64;
import java.util.Base64.Decoder;
import java.util.Date;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTCreationException;

import org.apache.camel.Exchange;
import org.apache.camel.ExchangeProperty;
import org.apache.camel.Header;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kubernetes.KubernetesConstants;
import org.apache.camel.support.builder.PredicateBuilder;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

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

    String generateAuth(
        @ExchangeProperty(GitHubApp.PEM) String pem,
        @ExchangeProperty("app_id") String appId)
        throws IOException {

        try (PEMParser parser = new PEMParser(new StringReader(pem))) {

            // read the private key
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            KeyPair kp = converter.getKeyPair((PEMKeyPair) parser.readObject());
            RSAPrivateKey key = (RSAPrivateKey) kp.getPrivate();
        
            Date date = new Date();

            // create and sign JWT with private key
            return "Bearer " + JWT.create()
                .withIssuedAt(date)
                .withExpiresAt(new Date(date.getTime() + (10 * 60 * 1000))) // 10 mins 
                .withIssuer(appId)
                .sign(Algorithm.RSA256(null, key));
        }
        catch (JWTCreationException e) {
            throw new IOException("Error creating JWT token", e);
        }
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
                    .to("direct:handle-event")
                .otherwise()
                    .removeHeaders("*")
                    .setBody(simple("${null}"));

        from("direct:handle-event")

            .onException(DecoderException.class)
            .onException(GeneralSecurityException.class)
            .onException(IOException.class)
                .handled(true)
                .removeHeaders("*")
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(404))
                .transform(constant("Not found"))
            .end()

            // save values for use
            .setProperty("app_id", simple("${body[installation][app_id]}"))
            .setProperty("id", simple("${body[installation][id]}"))
            
            // get app secrets
            .to("direct:get-secret")

            // validate the event
            .bean(this, "validateSignature")

            // request for an access token
            .removeHeaders("*")
            .setHeader("Authorization", method(this, "generateAuth"))
            .setBody(simple("${null}"))
            .toD("https://api.github.com/app/installations/${exchangeProperty[id]}/access_tokens?httpMethod=POST")
            .unmarshal().json()
            .setProperty("token", simple("${body[token]}"))

            // restore original payload
            .setBody(exchangeProperty("payload"))
            .log("TODO: ${body}");

        from("direct:get-secret")

            .setHeader(KubernetesConstants.KUBERNETES_NAMESPACE_NAME, simple("${env:NAMESPACE}"))
            .setHeader(KubernetesConstants.KUBERNETES_SECRET_NAME, simple("github-app-${exchangeProperty[app_id]}"))

            .to("kubernetes-secrets:///?kubernetesClient=#kubernetesClient&operation=getSecret")
            .process(exchange -> {

                Secret secret = exchange.getIn().getBody(Secret.class);
                if (secret != null) {

                    Decoder decoder = Base64.getDecoder();
                    Map<String, String> data = secret.getData();
                    
                    exchange.setProperty(GitHubApp.WEBHOOK_SECRET, decoder.decode(data.get(GitHubApp.WEBHOOK_SECRET)));
                    exchange.setProperty(GitHubApp.PEM, decoder.decode(data.get(GitHubApp.PEM)));
                }
            });
    }
}
