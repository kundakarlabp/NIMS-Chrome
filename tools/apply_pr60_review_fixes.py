from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


def fix_observer() -> None:
    path = Path("shared/nims-web/nimsPassiveObserver.js")
    text = path.read_text(encoding="utf-8")
    old = '''    if (!doc || !doc.querySelectorAll) return [];
    return Array.prototype.slice.call(doc.querySelectorAll("tr")).filter(function (row) {
      return isVisible(row) && /view\\s*report/i.test(compactText(row.innerText || row.textContent));
    }).map(function (_row, index) { return { row_index: index }; });'''
    new = '''    if (!doc || !doc.querySelectorAll) return [];
    var rows = Array.prototype.slice.call(doc.querySelectorAll("tr"));
    var matched = [];
    for (var index = 0; index < rows.length; index += 1) {
      var row = rows[index];
      if (isVisible(row) && /view\\s*report/i.test(compactText(row.innerText || row.textContent))) {
        matched.push({ row_index: index });
      }
    }
    return matched;'''
    path.write_text(replace_once(text, old, new, "fallback row index"), encoding="utf-8")


def fix_main_activity() -> None:
    path = Path("mobile/android/app/src/main/java/org/kundakarlab/nimsfastsummarymobile/MainActivity.kt")
    text = path.read_text(encoding="utf-8")
    expression = "appStateValue.ordinal < AppState.REPORT_PAGE_READY.ordinal"
    count = text.count(expression)
    if count < 1:
        raise RuntimeError("state ordinal expression not found")
    text = text.replace(expression, "appStateValue in PRE_REPORT_STATES")

    anchor = '''        private const val MAX_FETCHED_REPORT_BYTES = 25 * 1024 * 1024
    }'''
    replacement = '''        private const val MAX_FETCHED_REPORT_BYTES = 25 * 1024 * 1024
        private val PRE_REPORT_STATES = setOf(
            AppState.NEED_HELPER_SETTINGS,
            AppState.HELPER_READY,
            AppState.NIMS_LOGIN
        )
    }'''
    text = replace_once(text, anchor, replacement, "pre-report states")
    path.write_text(text, encoding="utf-8")


def main() -> None:
    fix_observer()
    fix_main_activity()


if __name__ == "__main__":
    main()
