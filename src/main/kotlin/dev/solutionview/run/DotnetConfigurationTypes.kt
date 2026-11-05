package dev.solutionview.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.project.Project
import dev.solutionview.SolutionIcons

/** "Run > Edit Configurations > + > .NET Project": runs a project with `dotnet run`. */
class DotnetRunConfigurationType : ConfigurationTypeBase(
    ID,
    ".NET Project",
    "Run a .NET project with dotnet run",
    SolutionIcons.CSharpProject,
) {
    init {
        addFactory(object : ConfigurationFactory(this) {
            override fun getId(): String = ID
            override fun createTemplateConfiguration(project: Project): RunConfiguration =
                DotnetRunConfiguration(project, this, ".NET Project")
            override fun getOptionsClass(): Class<out BaseState> = DotnetRunOptions::class.java
        })
    }

    val factory: ConfigurationFactory get() = configurationFactories.single()

    companion object {
        const val ID = "SolutionViewDotnetRun"

        fun getInstance(): DotnetRunConfigurationType =
            ConfigurationTypeUtil.findConfigurationType(DotnetRunConfigurationType::class.java)
    }
}
