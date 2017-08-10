/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.logging.console.jvm

import org.gradle.integtests.fixtures.AbstractConsoleFunctionalSpec
import org.gradle.integtests.fixtures.executer.GradleContextualExecuter
import org.gradle.integtests.fixtures.executer.GradleHandle
import org.gradle.test.fixtures.ConcurrentTestUtil
import org.gradle.test.fixtures.server.http.BlockingHttpServer
import org.junit.Rule
import spock.lang.IgnoreIf

@IgnoreIf({ GradleContextualExecuter.isParallel() })
class ConsoleJvmTestWorkerFunctionalTest extends AbstractConsoleFunctionalSpec {

    private static final int MAX_WORKERS = 4
    private static final String SERVER_RESOURCE_1 = 'test-1'
    private static final String SERVER_RESOURCE_2 = 'test-2'

    @Rule
    BlockingHttpServer server = new BlockingHttpServer()

    def setup() {
        executer.withArguments('--parallel', "--max-workers=$MAX_WORKERS")
        server.start()
    }

    def "shows test class execution in work-in-progress area of console for single project build"() {
        given:
        buildFile << testableJavaProject()
        file('src/test/java/org/gradle/Test1.java') << junitTest('Test1', SERVER_RESOURCE_1)
        file('src/test/java/org/gradle/Test2.java') << junitTest('Test2', SERVER_RESOURCE_2)
        def testExecution = server.expectConcurrentAndBlock(SERVER_RESOURCE_1, SERVER_RESOURCE_2)

        when:
        def gradleHandle = executer.withTasks('test').start()
        testExecution.waitForAllPendingCalls()

        then:
        ConcurrentTestUtil.poll {
            assert containsTestExecutionWorkInProgressLine(gradleHandle, ':test', 'org.gradle.Test1')
            assert containsTestExecutionWorkInProgressLine(gradleHandle, ':test', 'org.gradle.Test2')
        }

        testExecution.releaseAll()
        gradleHandle.waitForFinish()
    }

    def "shows test class execution in work-in-progress area of console for multi-project build"() {
        given:
        settingsFile << "include 'project1', 'project2'"
        buildFile << """
            subprojects {
                ${testableJavaProject()}
            }
        """
        file('project1/src/test/java/org/gradle/Test1.java') << junitTest('Test1', SERVER_RESOURCE_1)
        file('project2/src/test/java/org/gradle/Test2.java') << junitTest('Test2', SERVER_RESOURCE_2)
        def testExecution = server.expectConcurrentAndBlock(SERVER_RESOURCE_1, SERVER_RESOURCE_2)

        when:
        def gradleHandle = executer.withTasks('test').start()
        testExecution.waitForAllPendingCalls()

        then:
        ConcurrentTestUtil.poll {
            assert containsTestExecutionWorkInProgressLine(gradleHandle, ':project1:test', 'org.gradle.Test1')
            assert containsTestExecutionWorkInProgressLine(gradleHandle, ':project2:test', 'org.gradle.Test2')
        }

        testExecution.releaseAll()
        gradleHandle.waitForFinish()
    }

    def "shows abbreviated package when qualified test class is longer than 60 characters"() {
        given:
        buildFile << testableJavaProject()
        file('src/test/java/org/gradle/AdvancedJavaPackageAbbreviatingClassFunctionalTest.java') << junitTest('AdvancedJavaPackageAbbreviatingClassFunctionalTest', SERVER_RESOURCE_1)
        file('src/test/java/org/gradle/EvenMoreAdvancedJavaPackageAbbreviatingJavaClassFunctionalTest.java') << junitTest('EvenMoreAdvancedJavaPackageAbbreviatingJavaClassFunctionalTest', SERVER_RESOURCE_2)
        def testExecution = server.expectConcurrentAndBlock(SERVER_RESOURCE_1, SERVER_RESOURCE_2)

        when:
        def gradleHandle = executer.withTasks('test').start()
        testExecution.waitForAllPendingCalls()

        then:
        ConcurrentTestUtil.poll {
            assert containsTestExecutionWorkInProgressLine(gradleHandle, ':test', 'org...AdvancedJavaPackageAbbreviatingClassFunctionalTest')
            assert containsTestExecutionWorkInProgressLine(gradleHandle, ':test', '...EvenMoreAdvancedJavaPackageAbbreviatingJavaClassFunctionalTest')
        }

        testExecution.releaseAll()
        gradleHandle.waitForFinish()
    }

    private String junitTest(String testClassName, String serverResource) {
        """
            package org.gradle;

            import org.junit.Test;

            public class $testClassName {
                @Test
                public void longRunningTest() {
                    ${server.callFromBuild(serverResource)}
                }
            }
        """
    }

    static String testableJavaProject() {
        """
            apply plugin: 'java'
            
            repositories {
                jcenter()
            }
            
            dependencies {
                testCompile 'junit:junit:4.12'
            }
            
            tasks.withType(Test) {
                maxParallelForks = $MAX_WORKERS
            }
        """
    }

    static boolean containsTestExecutionWorkInProgressLine(GradleHandle gradleHandle, String taskPath, String testName) {
        gradleHandle.standardOutput.contains(workInProgressLine("> $taskPath > Executing test $testName"))
    }
}
