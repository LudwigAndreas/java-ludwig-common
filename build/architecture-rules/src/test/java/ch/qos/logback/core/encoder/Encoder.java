package ch.qos.logback.core.encoder;

/** Stand-in for Logback's encoder contract, so the fixtures need no Logback dependency. */
public interface Encoder<E> {

    byte[] encode(E event);
}
