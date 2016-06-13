package com.minds.metacommand.spawn

import com.metashift.config.GroovyRefreshableApplicationContext
import com.metashift.config.ServiceLocator
import com.metashift.context.Fileable
import com.metashift.modules.spawn.ISpawnService
import org.crsh.cli.Argument
import org.crsh.cli.Command
import org.crsh.cli.Required
import org.crsh.cli.Usage
import org.crsh.command.BaseCommand
/**
 * Created by navid on 12/19/14.
 */
@Usage("Will process all spawn .seed files found within the directory or subdirectories")
class spawn extends BaseCommand {

    @Command
    public void main(@Usage('The directory to scan for .seed(s)')
                     @Argument
                     @Required Fileable rootDirectory) {

        ISpawnService spawnService = ServiceLocator.locateCurrent(ISpawnService.class)
        GroovyRefreshableApplicationContext metaContext = ServiceLocator.locateCurrent(GroovyRefreshableApplicationContext.class)

        spawnService.processDirectory(rootDirectory.canonicalFile,[meta:metaContext,session:context.session])
    }

}