package com.github.oinsio.gnomish.adapter.check;

import com.github.oinsio.gnomish.gitobjects.BlobTooLargeException;
import com.github.oinsio.gnomish.gitobjects.GitObjects;
import com.github.oinsio.gnomish.gitobjects.MissingObjectException;
import com.github.oinsio.gnomish.gitobjects.ObjectId;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * One side of a pinned-path comparison of the {@link PinCheck} (FR16 of add-sandbox-core): the file
 * absent at the commit, present with its bytes, or over the read cap.
 *
 * <p>A plain class, not a record: a record's generated {@code equals}/{@code hashCode} would compare
 * the {@code bytes} array by reference rather than by content (Error Prone {@code
 * ArrayRecordComponent}), which is not this type's intended semantics.
 *
 * <p>Implements FR16 of add-sandbox-core.
 */
final class PinnedBlob {

    private final byte @Nullable [] bytes;
    private final boolean tooLarge;

    private PinnedBlob(byte @Nullable [] bytes, boolean tooLarge) {
        this.bytes = bytes;
        this.tooLarge = tooLarge;
    }

    /** The file at {@code path} in {@code commit}, read up to {@code cap} bytes. */
    static PinnedBlob read(GitObjects gitObjects, ObjectId commit, String path, long cap) {
        try {
            return new PinnedBlob(gitObjects.readBlob(commit, path, cap), false);
        } catch (MissingObjectException e) {
            return new PinnedBlob(null, false);
        } catch (BlobTooLargeException e) {
            return new PinnedBlob(null, true);
        }
    }

    /** Whether the file is over the read cap, so it cannot be compared. */
    boolean tooLarge() {
        return tooLarge;
    }

    /**
     * How {@code attempt}, the attempt commit's side, diverges from this side, the law commit's.
     *
     * @param attempt the attempt commit's side of the same path; never null
     * @return the words naming the divergence, or {@code null} when the two are byte-identical (both
     *     absent included)
     */
    @Nullable
    String divergence(PinnedBlob attempt) {
        if (bytes != null && attempt.bytes == null) {
            return "was removed relative to the base branch";
        }
        if (bytes == null && attempt.bytes != null) {
            return "is absent from the base branch";
        }
        if (bytes != null && !Arrays.equals(bytes, attempt.bytes)) {
            return "differs from the base branch";
        }
        return null;
    }
}
