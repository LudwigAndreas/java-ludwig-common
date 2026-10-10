package ru.ludwigandreas.notification.local;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import ru.ludwigandreas.testsupport.local.stub.PartnerStub;
import ru.ludwigandreas.testsupport.local.stub.StubAddress;

/**
 * The internal chat API, as a server the chat channel really calls.
 *
 * <p>The chat channel is not a {@code @LudwigRestClient}: it builds its own {@code RestClient} from
 * {@code ludwig.notification.channels.chat.base-url}, so this stub publishes its address under that
 * property instead of under a rest-client name.
 *
 * <p>It answers the one call the channel makes and nothing else. It is a belief about the chat API,
 * not a specification of it.
 */
public class ChatApiStub implements PartnerStub {

    /** This stub's name: {@code ludwig.local.stubs.chat.*} and {@code StubServers.stub("chat")}. */
    public static final String NAME = "chat";

    /** The path the chat channel posts to. */
    public static final String SEND_PATH = "/api/v1/messages";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public StubAddress address() {
        return StubAddress.property("ludwig.notification.channels.chat.base-url");
    }

    @Override
    public void register(WireMockServer server) {
        server.stubFor(post(urlPathEqualTo(SEND_PATH))
                .willReturn(okJson("{\"messageId\":\"local-chat-message\"}")));
    }
}
