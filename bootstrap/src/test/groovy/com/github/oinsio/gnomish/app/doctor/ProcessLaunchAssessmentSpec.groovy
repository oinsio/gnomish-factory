package com.github.oinsio.gnomish.app.doctor

import com.github.oinsio.gnomish.subprocess.ExecutableVerdict
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/** FR1, FR4 of add-doctor-command (design D4): the production assessment is the launch owner's own answer. */
class ProcessLaunchAssessmentSpec extends Specification {

    @TempDir
    Path dir

    def assessment = new ProcessLaunchAssessment()

    def "FR4: the helper verdict is the launch owner's"() {
        expect:
        assessment.helper() == ProcessLaunch.assessHelper()
    }

    def "FR4: an executable is resolved by the launch owner's rule on the PATH handed in"() {
        expect:
        assessment.executable('sh', dir, '/bin') == ProcessLaunch.assessExecutable('sh', dir, '/bin')
        assessment.executable('sh', dir, '/bin') instanceof ExecutableVerdict.Resolved
        assessment.executable('no-such-gnomish-tool', dir, dir.toString()) instanceof ExecutableVerdict.Unresolved
    }
}
