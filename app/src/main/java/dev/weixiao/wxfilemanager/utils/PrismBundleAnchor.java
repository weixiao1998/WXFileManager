package dev.weixiao.wxfilemanager.utils;

import io.noties.prism4j.annotations.PrismBundle;

@PrismBundle(
    include = {
        "clike", "c", "cpp", "csharp", "java", "kotlin", "javascript",
        "json", "markup", "css", "python", "sql", "yaml", "markdown",
        "go"
    },
    grammarLocatorClassName = ".PrismGrammarLocator"
)
final class PrismBundleAnchor {
    private PrismBundleAnchor() {
    }
}
