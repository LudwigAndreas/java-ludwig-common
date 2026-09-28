package ru.ludwigandreas.hotreload.core;

public class HotReloadException extends RuntimeException {

    public HotReloadException(String message) {
        super(message);
    }

    public HotReloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
