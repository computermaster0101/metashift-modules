package com.minds.metacommand.base
import com.metashift.context.*
import org.crsh.cli.Argument
import org.crsh.cli.Command
import org.crsh.cli.Required
import org.crsh.cli.Usage
import org.crsh.command.BaseCommand
import org.crsh.command.InvocationContext
import org.crsh.command.Pipe
/**
 * Created by navid on 12/19/14.
 */
@Usage("Allows data to be added and removed from the meta context")
class meta extends BaseCommand {

    @Usage("Takes the value from the pipe and adds it to the Meta Context with the given key.")
    @Command
    public Pipe<Object, Void> push(@Usage("The key to use when storing the object")
                                  @Argument
                                  @Required final String key) {

        return new Pipe<Object, Void>() {

            @Override
            void provide(Object element) throws Exception {
                Object old = MetaContext.put(key, element);
                if( old ) {
                    out << "swap:\n"
                    out << "  old: " << blue << "meta['${key}'] = ${old}" << reset << "\n"
                    out << "  new: " << magenta << "meta['${key}'] = ${element}" << reset << "\n"
                    out.flush()
                } else {
                    out << "put: " << blue << "meta['${key}'] = ${element}" << reset << "\n"
                    out.flush()
                }
                context.flush()
            }
        }
    }

    @Usage("Gets the value at the given key and send to the pipe.")
    @Command
    public void pull(InvocationContext<Object> context,
                     @Usage("The key to use to retrieve the object from the session")
                     @Argument
                     @Required MetaGraphReference reference) {

        context.provide(MetaContext.pull(reference))
    }

    @Usage("Removes key from meta")
    @Command
    public void remove(@Usage("The key to remove")
                       @Argument
                       @Required MetaGraphReference reference) {

        MetaContext.remove(reference)
        out << "del: " << red << "meta['${reference.name()}']" << reset << "\n"
        out.flush()
    }

}
