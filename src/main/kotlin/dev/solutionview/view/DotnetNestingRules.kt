package dev.solutionview.view

import com.intellij.ide.projectView.ProjectViewNestingRulesProvider

/**
 * Nests generated and companion files under their parent, as Rider and Visual Studio do.
 * A rule matches files with the same base name: `Foo.xaml` gets `Foo.xaml.cs` as a child.
 */
class DotnetNestingRules : ProjectViewNestingRulesProvider {
    override fun addFileNestingRules(consumer: ProjectViewNestingRulesProvider.Consumer) {
        val rules = listOf(
            ".xaml" to ".xaml.cs",
            ".axaml" to ".axaml.cs",
            ".razor" to ".razor.cs",
            ".razor" to ".razor.css",
            ".razor" to ".razor.js",
            ".cshtml" to ".cshtml.cs",
            ".cshtml" to ".cshtml.css",
            ".resx" to ".Designer.cs",
            ".cs" to ".Designer.cs",
            ".settings" to ".Designer.cs",
            ".json" to ".Development.json",
            ".json" to ".Staging.json",
            ".json" to ".Production.json",
            ".config" to ".Debug.config",
            ".config" to ".Release.config",
        )
        rules.forEach { (parent, child) -> consumer.addNestingRule(parent, child) }
    }
}
