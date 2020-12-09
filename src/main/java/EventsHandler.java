// camel-k: language=java

import org.apache.camel.builder.RouteBuilder;

public class EventsHandler extends RouteBuilder {

    @Override
    public void configure() throws Exception {

        from("direct:events")
            .log("Event: ${body}")
            .log("${headers}");
    }
}
