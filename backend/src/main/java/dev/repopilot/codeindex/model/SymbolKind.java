package dev.repopilot.codeindex.model;

/** The kinds of declaration RepoPilot indexes. */
public enum SymbolKind {
    CLASS,
    INTERFACE,
    ENUM,
    RECORD,
    ANNOTATION,
    METHOD,
    CONSTRUCTOR,
    FIELD,
    ENUM_CONSTANT;

    public boolean isType() {
        return this == CLASS || this == INTERFACE || this == ENUM || this == RECORD || this == ANNOTATION;
    }

    public boolean isCallable() {
        return this == METHOD || this == CONSTRUCTOR;
    }
}
