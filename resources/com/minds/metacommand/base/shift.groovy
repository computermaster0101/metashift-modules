import com.metashift.metadata.model.IMetadataReader
import com.metashift.metadata.model.SimpleMetadataReader
import com.metashift.crash.ObservablePipeBuilder
import com.metashift.modules.spawn.metadata.ClassMetadataModel
import org.crsh.cli.Command
import org.crsh.cli.Usage
import org.crsh.command.BaseCommand
import org.crsh.command.Pipe
import org.objectweb.asm.ClassReader
import org.springframework.core.io.Resource
import rx.functions.Func1
/**
 * Created by navid on 12/19/14.
 */
@Usage("Applies object transformations for streams of data")
class shift extends BaseCommand{



    @Usage("Ends the pipe by discarding all data sent to it. This allows you to prevent objecs from being printed by CRaSH")
    @Command
    public Pipe<Object,Void> end(){
        return new Pipe<Object, Void>() {
            @Override
            void provide(Object element) throws Exception {
                // noop , throw it away
                out.flush()
                context.flush()
            }
        }
    }

    @Usage("Maps metaReader to a map containing information for all @Entity objects sent to the stream")
    @Command
    public Pipe<IMetadataReader,ClassMetadataModel> metaReaderToModalMeta() {
        // TODO: Add more fields to map that is output
        def builder = new ObservablePipeBuilder<>()

        builder.filter({IMetadataReader obj ->

            obj.getAnnotationMetadata().hasAnnotation("Entity")

        }as Func1).map({ IMetadataReader obj ->
            return new ClassMetadataModel(obj)
        } as Func1)

        builder.build(true)
    }

    @Usage("Creates a IMetadataReader for any class Resource found on the pipe")
    @Command
    public Pipe<Resource,IMetadataReader> resourceToMetaReader(){

        ObservablePipeBuilder<Resource,IMetadataReader> builder = new ObservablePipeBuilder<>()
        builder.map({ Resource res ->
                new SimpleMetadataReader(
                        new ClassReader(res.getInputStream()),
                                        res.getClass().getClassLoader())
            } as Func1)

        builder.build(true)
    }

}
