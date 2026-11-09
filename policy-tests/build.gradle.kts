plugins { kotlin("jvm") version "2.2.10" }
kotlin {
    jvmToolchain(17)
    sourceSets.main {
        kotlin.srcDir("../android/app/src/main/java")
        kotlin.include("dev/personal/phoneagent/adapters/*.kt")
        kotlin.include("dev/personal/phoneagent/agent/Models.kt", "dev/personal/phoneagent/agent/ActionPolicy.kt",
            "dev/personal/phoneagent/agent/ApprovalGate.kt", "dev/personal/phoneagent/agent/BankWorkflow.kt",
            "dev/personal/phoneagent/agent/SendEvidence.kt", "dev/personal/phoneagent/agent/RequestPlanner.kt",
            "dev/personal/phoneagent/agent/Channels.kt")
    }
    sourceSets.test { kotlin.srcDir("../android/app/src/test/java") }
}
dependencies {
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
tasks.test { useJUnit(); testLogging { events("passed", "skipped", "failed") } }
