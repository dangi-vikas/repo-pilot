package dev.repopilot.codeindex.model;

/** Java declaration visibility, including the implicit package-private case. */
public enum SymbolVisibility {
    PUBLIC,
    PROTECTED,
    PACKAGE_PRIVATE,
    PRIVATE;

    public static SymbolVisibility fromModifiers(boolean isPublic, boolean isProtected, boolean isPrivate) {
        if (isPublic) {
            return PUBLIC;
        }
        if (isProtected) {
            return PROTECTED;
        }
        if (isPrivate) {
            return PRIVATE;
        }
        return PACKAGE_PRIVATE;
    }
}
