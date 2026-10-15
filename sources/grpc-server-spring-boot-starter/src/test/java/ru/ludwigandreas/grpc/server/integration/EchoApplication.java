package ru.ludwigandreas.grpc.server.integration;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.grpc.server.integration.support.EchoService;
import ru.ludwigandreas.grpc.server.integration.support.TestConflictException;
import ru.ludwigandreas.webcore.problem.ExceptionProblemMapper;
import ru.ludwigandreas.webcore.problem.ProblemDefinition;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * The application under test: one gRPC service and one contributed problem mapper - the same
 * mapper bean an HTTP service would contribute, with nothing gRPC-specific about it.
 */
@SpringBootConfiguration
public class EchoApplication {

    @Bean
    public EchoService echoService() {
        return new EchoService();
    }

    @Bean
    public ExceptionProblemMapper testConflictMapper() {
        return ExceptionProblemMapper.forType(TestConflictException.class,
                failure -> ProblemDefinition.of(ProblemStatus.CONFLICT, TestConflictException.CODE));
    }
}
