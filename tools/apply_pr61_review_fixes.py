from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


def update_observer() -> None:
    path = Path("shared/nims-web/nimsPassiveObserver.js")
    text = path.read_text(encoding="utf-8")
    text = replace_once(
        text,
        '''  function hasPasswordInput(doc) {
    return Boolean(doc && doc.querySelector && doc.querySelector("input[type='password']"));
  }''',
        '''  function hasPasswordInput(doc) {
    if (!doc || !doc.querySelectorAll) return false;
    return Array.prototype.slice.call(doc.querySelectorAll("input[type='password']")).some(isVisible);
  }''',
        "visible password detection",
    )
    text = replace_once(
        text,
        '''    var allRows = Array.prototype.slice.call(doc.querySelectorAll("tr"));
    var clickNodes = Array.prototype.slice.call(doc.querySelectorAll("[onclick]"));''',
        '''    var allRows = Array.prototype.slice.call(doc.querySelectorAll("tr"));
    var rowIndexByElement = new Map();
    for (var rowIndex = 0; rowIndex < allRows.length; rowIndex += 1) {
      rowIndexByElement.set(allRows[rowIndex], rowIndex);
    }
    var clickNodes = Array.prototype.slice.call(doc.querySelectorAll("[onclick]"));''',
        "row index map",
    )
    text = replace_once(
        text,
        '''      var rowIndex = allRows.indexOf(row);
      if (rowIndex < 0) continue;''',
        '''      var currentRowIndex = rowIndexByElement.get(row);
      if (typeof currentRowIndex !== "number") continue;''',
        "constant-time row lookup",
    )
    text = replace_once(text, "        row_index: rowIndex,", "        row_index: currentRowIndex,", "row output index")
    path.write_text(text, encoding="utf-8")


def update_url_policy() -> None:
    path = Path("mobile/android/app/src/main/java/org/kundakarlab/nimsfastsummarymobile/security/NimsUrlPolicy.kt")
    text = path.read_text(encoding="utf-8")
    text = replace_once(
        text,
        '''    fun isTrustedLegacyPageScript(currentPageUrl: String, requestedUrl: String): Boolean {
        if (classifyUrl(currentPageUrl) != UrlClassification.ALLOWED_NIMS) return false
        if (requestedUrl.length !in 12..4096 || requestedUrl.any { it.code < 0x20 }) return false
        return requestedUrl.trimStart().startsWith("javascript:", ignoreCase = true)
    }''',
        '''    fun isTrustedLegacyPageScript(currentPageUrl: String, requestedUrl: String): Boolean {
        if (classifyUrl(currentPageUrl) != UrlClassification.ALLOWED_NIMS) return false
        val trimmed = requestedUrl.trimStart()
        if (trimmed.length !in 11..4096 || trimmed.any { it.code < 0x20 }) return false
        return trimmed.startsWith("javascript:", ignoreCase = true)
    }''',
        "trimmed legacy action policy",
    )
    path.write_text(text, encoding="utf-8")


def main() -> None:
    update_observer()
    update_url_policy()


if __name__ == "__main__":
    main()
