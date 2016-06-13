package com.minds.metacommand.base

import com.metashift.template.GroovyTemplateUtil
import com.metashift.context.Fileable
import groovy.text.Template
import org.crsh.cli.Argument
import org.crsh.cli.Command
import org.crsh.cli.Required
import org.crsh.cli.Usage
import org.crsh.command.BaseCommand

/**
 * Created by navid on 12/19/14.
 */
@Usage("Creates a new liquid command built on a shell executor template")
class liquid extends BaseCommand {


    @Usage('Creates a new command from a template that is immediately available')
    @Command
    public void create(
            @Usage('The template to use to create the new command')
            @Argument
            @Required Fileable templateFile,
            @Usage('The name of the command that is being created')
            @Argument
            @Required String commandName) {

        GroovyTemplateUtil.renderToTempCmd(templateFile.withContext().absoluteFile, context.session ,commandName)
    }

    @Usage('Creates a new command from a template that is immediately available')
    @Command
    public void print(@Usage('The template to print the parameters for')
                                @Argument
                                @Required Fileable templateFile){

        File actual = templateFile.withContext().absoluteFile
        Map defaults = GroovyTemplateUtil.loadDefaults(actual)

        Template template = GroovyTemplateUtil.createTemplate(actual)

        out << template.make(defaults)
        out.flush()
    }


}
