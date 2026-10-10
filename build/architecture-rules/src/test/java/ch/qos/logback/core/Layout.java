package ch.qos.logback.core;

/** Stand-in for Logback's layout contract, so the fixtures need no Logback dependency. */
public interface Layout<E> {

    String doLayout(E event);
}
