from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


def patch_activity() -> None:
    path = Path("mobile/android/app/src/main/java/org/kundakarlab/nimsfastsummarymobile/ChromeModeActivity.kt")
    text = path.read_text(encoding="utf-8")
    text = text.replace("import androidx.compose.foundation.layout.height\n", "")
    text = text.replace("import androidx.compose.runtime.remember\n", "")
    text = replace_once(
        text,
        "                if (activeJob == coroutineContext[Job]) activeJob = null",
        "                activeJob = null",
        "active job cleanup",
    )
    text = replace_once(
        text,
        '''                    runCatching { fetchAndParse(request, index + 2, total) }
                        .getOrElse { errorReport(request.row, it.message ?: "Report failed") }''',
        '''                    try {
                        fetchAndParse(request, index + 2, total)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        errorReport(request.row, error.message ?: "Report failed")
                    }''',
        "structured cancellation",
    )
    path.write_text(text, encoding="utf-8")


def patch_extractor() -> None:
    path = Path("mobile/android/app/src/main/java/org/kundakarlab/nimsfastsummarymobile/OnDemandNimsExtractor.kt")
    text = path.read_text(encoding="utf-8")
    text = replace_once(
        text,
        '''    internal fun decodeResult(raw: String?): Result<JSONObject> = runCatching {
        val value = raw.orEmpty().trim()
        require(value.isNotBlank() && value != "null") { "NIMS did not return an extraction result." }
        val decoded = JSONArray("[$value]").getString(0)
        JSONObject(decoded)
    }

    companion object {
        internal const val ASSET_NAME = "nimsOnDemandExtractor.js"
    }''',
        '''    companion object {
        internal const val ASSET_NAME = "nimsOnDemandExtractor.js"

        internal fun decodeResult(raw: String?): Result<JSONObject> = runCatching {
            val value = raw.orEmpty().trim()
            require(value.isNotBlank() && value != "null") { "NIMS did not return an extraction result." }
            val decoded = JSONArray("[$value]").getString(0)
            JSONObject(decoded)
        }
    }''',
        "static decoder",
    )
    path.write_text(text, encoding="utf-8")


def main() -> None:
    patch_activity()
    patch_extractor()


if __name__ == "__main__":
    main()
