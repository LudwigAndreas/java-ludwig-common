package ru.ludwigandreas.grpc.server.config;

import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.grpc.server.properties.GrpcServerProperties;

/**
 * The slice's own half: a channel to the in-process server under test.
 *
 * <p>Listed only in {@link LudwigGrpcServerTest}'s imports file, never in the starter's, so it
 * cannot reach a running service.
 */
@AutoConfiguration
public class GrpcServerTestAutoConfiguration {

    /**
     * A channel to the server under test.
     *
     * @param properties the server configuration, carrying the in-process name
     * @return the channel; closed with the context
     */
    @Bean(destroyMethod = "shutdownNow")
    @ConditionalOnMissingBean
    public ManagedChannel ludwigGrpcServerTestChannel(GrpcServerProperties properties) {
        return InProcessChannelBuilder.forName(properties.getInProcessName()).build();
    }
}
