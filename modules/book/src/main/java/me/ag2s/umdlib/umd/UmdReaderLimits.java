package me.ag2s.umdlib.umd;

/** Immutable allocation limits used while parsing an untrusted UMD file. */
public final class UmdReaderLimits {

    private static final int MIN_DEFAULT_CONTENT_BYTES = 4 * 1024 * 1024;
    private static final int MAX_DEFAULT_CONTENT_BYTES = 128 * 1024 * 1024;
    private static final int DEFAULT_BLOCK_BYTES = 1024 * 1024;
    private static final int DEFAULT_ADDITIONAL_PAYLOAD_BYTES = 32 * 1024 * 1024;
    private static final int DEFAULT_MAX_CHAPTERS = 100_000;
    private static final int MAX_DEFAULT_TITLE_BYTES = 16 * 1024 * 1024;

    private final int maxContentBytes;
    private final int maxDecompressedBlockBytes;
    private final int maxAdditionalPayloadBytes;
    private final int maxChapterCount;
    private final int maxTitleBytes;

    public UmdReaderLimits(
            int maxContentBytes,
            int maxDecompressedBlockBytes,
            int maxAdditionalPayloadBytes
    ) {
        this(
                maxContentBytes,
                maxDecompressedBlockBytes,
                maxAdditionalPayloadBytes,
                DEFAULT_MAX_CHAPTERS,
                Math.min(MAX_DEFAULT_TITLE_BYTES, maxContentBytes)
        );
    }

    public UmdReaderLimits(
            int maxContentBytes,
            int maxDecompressedBlockBytes,
            int maxAdditionalPayloadBytes,
            int maxChapterCount,
            int maxTitleBytes
    ) {
        if (maxContentBytes <= 0) {
            throw new IllegalArgumentException("maxContentBytes must be positive");
        }
        if (maxDecompressedBlockBytes <= 0) {
            throw new IllegalArgumentException("maxDecompressedBlockBytes must be positive");
        }
        if (maxAdditionalPayloadBytes <= 0) {
            throw new IllegalArgumentException("maxAdditionalPayloadBytes must be positive");
        }
        if (maxChapterCount <= 0) {
            throw new IllegalArgumentException("maxChapterCount must be positive");
        }
        if (maxTitleBytes <= 0) {
            throw new IllegalArgumentException("maxTitleBytes must be positive");
        }
        this.maxContentBytes = maxContentBytes;
        this.maxDecompressedBlockBytes = maxDecompressedBlockBytes;
        this.maxAdditionalPayloadBytes = maxAdditionalPayloadBytes;
        this.maxChapterCount = maxChapterCount;
        this.maxTitleBytes = maxTitleBytes;
    }

    public static UmdReaderLimits defaults() {
        long heapBudget = Runtime.getRuntime().maxMemory() / 4L;
        long contentBudget = Math.max(
                MIN_DEFAULT_CONTENT_BYTES,
                Math.min(MAX_DEFAULT_CONTENT_BYTES, heapBudget)
        );
        int chapterBudget = (int) Math.min(
                DEFAULT_MAX_CHAPTERS,
                Math.max(1_000L, contentBudget / 64L)
        );
        return new UmdReaderLimits(
                (int) contentBudget,
                DEFAULT_BLOCK_BYTES,
                (int) Math.min(DEFAULT_ADDITIONAL_PAYLOAD_BYTES, contentBudget),
                chapterBudget,
                (int) Math.min(MAX_DEFAULT_TITLE_BYTES, Math.max(1L, contentBudget / 4L))
        );
    }

    public int getMaxContentBytes() {
        return maxContentBytes;
    }

    public int getMaxDecompressedBlockBytes() {
        return maxDecompressedBlockBytes;
    }

    public int getMaxAdditionalPayloadBytes() {
        return maxAdditionalPayloadBytes;
    }

    public int getMaxChapterCount() {
        return maxChapterCount;
    }

    public int getMaxTitleBytes() {
        return maxTitleBytes;
    }
}
