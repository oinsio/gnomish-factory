package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.BasePin
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.gitobjects.ObjectId
import java.nio.file.Path

/**
 * What a spec needs to call {@code TaskRepository.createTask} the way production calls it since
 * FR15 of add-base-ref-resolution: a start point that is already a commit, and a pin that is
 * metadata beside it. Before that revision a spec passed a base <em>name</em> and let the adapter
 * resolve it; the port no longer accepts one, and this fixture is where the single peel a
 * production caller performs is reproduced for a spec — once, rather than as a {@code rev-parse}
 * open-coded in forty spec files.
 *
 * <p>Deliberately not a convenience that hides the difference: {@link #commit} is a real peel
 * against a real repository, so a spec that names a revision the clone does not hold fails the same
 * way a run would.
 */
final class TaskStart {

    /**
     * A well-formed commit name for specs whose task repository is a fake or a mock and never
     * looks the object up. Never use it against a real repository.
     */
    static final ObjectId ANY = ObjectId.of('0123456789abcdef0123456789abcdef01234567')

    private TaskStart() {}

    /** The commit {@code revision} names in the repository at {@code repoDir} — the caller's single peel. */
    static ObjectId commit(Path repoDir, String revision) {
        def result = new GitProcessRunner().run(repoDir, 'rev-parse', '--verify', revision + '^{commit}')
        assert result.exitCode() == 0: "spec fixture: '${revision}' resolves to no commit in ${repoDir}"
        ObjectId.of(result.stdout().forParsing().trim())
    }

    /**
     * A pin carrying no kind — the shape the manual tier writes and the shape every document
     * written before the kind existed carries, so a spec using it exercises the classify-at-resume
     * path (D7 of add-base-ref-resolution).
     */
    static BasePin pin(String ref, BaseRule rule) {
        new BasePin(ref, null, rule)
    }
}
