package dev.ifelse.zirion.genesis.config;

/** Safe diagnostic with a source/JSON pointer; no configuration values or credentials in messages. */
public final class ConfigurationException extends IllegalArgumentException {
    private final String code;
    private final String path;

    public ConfigurationException(String code, String path, String message) {
        super(code + " at " + path + ": " + message);
        this.code = code;
        this.path = path;
    }

    public String code() { return code; }
    public String path() { return path; }
}
