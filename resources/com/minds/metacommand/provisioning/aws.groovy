package com.minds.metacommand.provisioning
import com.amazonaws.AmazonWebServiceRequest
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.auth.AWSCredentialsProviderChain
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.handlers.AsyncHandler
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions
import com.amazonaws.resources.ServiceBuilder
import com.amazonaws.resources.ec2.EC2
import com.amazonaws.resources.ec2.Instance
import com.amazonaws.resources.ec2.InternetGateway
import com.amazonaws.resources.ec2.Subnet
import com.amazonaws.resources.ec2.Vpc
import com.amazonaws.resources.ec2.VpcCollection
import com.amazonaws.services.ec2.AmazonEC2AsyncClient
import com.amazonaws.services.ec2.AmazonEC2Client
import com.amazonaws.services.ec2.model.DeleteInternetGatewayRequest
import com.amazonaws.services.ec2.model.DescribeVpcsRequest
import com.amazonaws.services.ec2.model.DetachInternetGatewayRequest
import com.amazonaws.services.ec2.model.Filter
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient
import com.metashift.aws.AwsResponse
import com.metashift.context.MetaContext
import com.metashift.crash.completers.AwsEc2AsyncCompleter
import com.metashift.crash.completers.AwsResourceCompleter
import com.metashift.context.MetaGraphReference
import org.crsh.cli.Argument
import org.crsh.cli.Command
import org.crsh.cli.Required
import org.crsh.cli.Usage
import org.crsh.command.BaseCommand
import org.crsh.command.InvocationContext
import org.crsh.command.Pipe
import org.crsh.command.ScriptException

import java.util.function.Consumer

/**
 * Created by navid on 12/19/14.
 */
@Usage("Provides AWS specific functionality for provisioning")
class aws extends BaseCommand {

    public static AWS_CREDENTIALS = 'awsCredentials'

    public static AWS_CREDENTIALS_PROVIDER = 'awsCredentialsProvider'

    public static AWS_CLIENT = 'awsClient'

    public static AWS_CLIENT_ASYNC = 'awsClientAsync'

    public static AWS_EC2_INSTANCE = 'ec2Client'

    public static AWS_EC2_REGION = 'ec2Region'

    @Usage(""" Loads the AWS credential profiles file from the default location (~/.aws/credentials)
               Credentials will be stored in the CommandContext.session
               Credentials can be unloaded by call unloadCredentials
               See: http://java.awsblog.com/post/TxRE9V31UFN860/Secure-Local-Development-with-the-ProfileCredentialsProvider
           """)
    @Command
    AWSCredentials loadCredentials(@Usage('The name of the aws profile to use or [default] if none is provided')
                                   @Argument String profile) {

       AWSCredentialsProvider credsProvider =  new AWSCredentialsProviderChain(
            // Can check for EC2 instance profile credentials.
            // This would be handy if running on the actual instance. check for performance impl to leave on always
            //new InstanceProfileCredentialsProvider(),

            // If we're not on an EC2 instance, fall back to checking for
            // credentials in the local credentials profile file.
            new ProfileCredentialsProvider(profile)
        )

        AWSCredentials creds = credsProvider.getCredentials()
        context.session[AWS_CREDENTIALS] = creds
        MetaContext.put(AWS_CREDENTIALS,creds,false)
        initClients()

        out << "Credentials Loaded"

        creds
    }

    @Usage('Loads a EC2 instance into the Meta Context for use later')
    @Command
    static void load(@Usage('The AWS region string. Can be any of us-east-1, us-west-1, us-west-2')
                         @Argument String region,
                     @Usage('The AWS profile to load from credentials file. If empty we use [default]')
                         @Argument String profile){

        if ( MetaContext.contains(AWS_EC2_INSTANCE) ){
            throw new ScriptException('Aws already loaded')
        }

        // FIXME: should make this configurable at runtime.
        Regions regions = Regions.US_EAST_1
        if (region){
            regions = Regions.fromName(region)
        }

        if(!profile) {
            profile = "default"
        }
        AWSCredentialsProvider credsProvider =  new AWSCredentialsProviderChain(
            // Can check for EC2 instance profile credentials.
            // This would be handy if running on the actual instance. check for performance impl to leave on always
            //new InstanceProfileCredentialsProvider(),

            // If we're not on an EC2 instance, fall back to checking for
            // credentials in the local credentials profile file.
            new ProfileCredentialsProvider(profile)
        )
        MetaContext.put(AWS_CREDENTIALS_PROVIDER,credsProvider,false)

        AWSCredentials creds = credsProvider.getCredentials()
        MetaContext.put(AWS_CREDENTIALS,creds,false)

        EC2 ec2 = ServiceBuilder.forService(EC2.class)
                .withRegion(Region.getRegion(regions))
                .withCredentials(credsProvider)
                .build()

        MetaContext.put(AWS_EC2_INSTANCE,ec2,false)
        MetaContext.put(AWS_EC2_REGION,regions.getName(),false)
    }


    @Usage("Provides access to the EC2 Resource")
    @Command
    void ec2(InvocationContext<Object> invocationContext,
             @Usage('The EC2 Resource methodName to execute.')
             @Argument(completer = AwsResourceCompleter.class)
             @Required String methodName,
             @Usage('The root metaGraph that should be provided for the action during execution')
             @Argument MetaGraphReference metaGraph){

        def value = metaGraph?.resolve()

        Object ret = ec2.invokeMethod(methodName,value)

        if (ret){
            invocationContext.provide(ret)
        }
    }

    static EC2 ec2(){
        if (!MetaContext.contains(AWS_EC2_INSTANCE)){
            load(null)
        }
        (EC2)MetaContext.pull(AWS_EC2_INSTANCE)
    }

    @Usage("Provides access to the EC2Client")
    @Command
    void ec2Old(InvocationContext<AwsResponse> invocationContext,
             @Usage('The AmazonEC2Client methodName to execute.')
             @Argument(completer = AwsEc2AsyncCompleter.class)
             @Required String methodName,
             @Usage('The root metaGraph that should be provided for the action during execution')
             @Argument
             MetaGraphReference metaGraph){

        def value = metaGraph?.resolve()

        if (value && !(value instanceof AmazonWebServiceRequest)){
            throw new ScriptException('metaGraph provided must be a AmazonWebServiceRequest')
        }

        doAwsRequest(invocationContext,methodName,(AmazonWebServiceRequest)value)
    }


    @Usage("Calls the amazon async client ")
    @Command
    public Pipe<AmazonWebServiceRequest,AwsResponse> ec2Flowz(@Usage('The AmazonEC2Client methodName to start creating request object for')
                                                               @Argument(completer = AwsEc2AsyncCompleter.class)
                                                               @Required String methodName){

        new Pipe<AmazonWebServiceRequest, AwsResponse>() {

            @Override
            void provide(AmazonWebServiceRequest request) throws Exception {
                doAwsRequest(context,methodName,request)
            }
        }
    }

    @Usage("Deletes all resources that were created for this current AWS session")
    @Command
    public void deleteAllForSession(){
        String creationId = MetaContext.pull('creationId')
        Map<String,Object> awsSession = (Map<String,Object>)MetaContext.pull(creationId)
        EC2 ec2 = MetaContext.pull(EC2.class)
        Filter filter = (Filter) awsSession.get('activeCreationIdFilter')

        if (creationId){
            out << 'Session creation Id: '<< creationId << ' will delete VPC\'s\n'
            out.flush()

            VpcCollection vpcs = ec2.getVpcs(new DescribeVpcsRequest().withFilters(filter))

            out<< 'Found ' <<  vpcs.size() << 'VPC\'s for this session\n'

            vpcs.forEach(new Consumer<Vpc>() {
                @Override
                void accept(Vpc vpc) {
                    out << red << 'Deleting All Objects in VPC id: ' << blue << vpc.getId() << reset << '\n'
                    out.flush()


                    vpc.getInstances().forEach(new Consumer<Instance>() {
                        @Override
                        void accept(Instance instance) {

                            out << red << 'Terminating Instance id: ' << blue << instance.getId() << reset << '\n'
                            instance.terminate()

                        }
                    })

                    vpc.getSubnets().forEach(new Consumer<Subnet>() {
                        @Override
                        void accept(Subnet subnet) {
                            out << red << 'Deleting Subnet id:' << blue << subnet.getId() << reset << '\n'
                            subnet.delete()
                        }
                    })

                    vpc.getInternetGateways().forEach(new Consumer<InternetGateway>() {
                        @Override
                        void accept(InternetGateway internetGateway) {
                            out << red << 'Detaching Internet Gateways id: ' << blue << internetGateway.getId() << reset << '\n'
                            internetGateway.detachFromVpc(new DetachInternetGatewayRequest().withVpcId(vpc.getId()))
                            internetGateway.delete(new DeleteInternetGatewayRequest())
                        }
                    })

                    out << red << 'Deleting VPC id:' << blue << vpc.getId() << reset << '\n'
                    vpc.delete()
                }
            })

        }
    }

    private doAwsRequest(InvocationContext<AwsResponse> invocationContext,String methodName,AmazonWebServiceRequest request){

        AsyncHandler<AmazonWebServiceRequest,Object> handler = new AsyncHandler<AmazonWebServiceRequest, Object>() {
            @Override
            void onError(Exception exception) {
                invocationContext.provide(new AwsResponse(exception: exception))
                invocationContext.flush()
            }

            @Override
            void onSuccess(AmazonWebServiceRequest req, Object o) {
                invocationContext.provide(new AwsResponse(request: req,result: o))
                invocationContext.flush()
            }
        }

        def args = request ? [(Object)request] : []
        def amazonClient
        def async = false

        if (methodName.toLowerCase().endsWith("async")){
            args.add(handler)
            amazonClient = ec2AsyncClient()
            async = true
        }else{
            amazonClient = ec2Client()
        }

        def ret = null
        try {
            ret = amazonClient.invokeMethod(methodName, args)
        }catch(Exception e){
            invocationContext.provide(new AwsResponse(exception: e))
        }

        if (!async && ret){
            invocationContext.provide(new AwsResponse(request: request,result: ret))
        }
    }

    private EC2 getEc2(){
        (EC2)MetaContext.pull(AWS_EC2_INSTANCE)
    }

    private initClients(){
        // MetaContext doesn't have access to these classes for some reason.
        if (!context.session[AWS_CLIENT]){
            context.session[AWS_CLIENT] = createEC2Client()
            MetaContext.put(AWS_CLIENT,context.session[AWS_CLIENT],false)
        }

        if (!context.session[AWS_CLIENT_ASYNC]){
            context.session[AWS_CLIENT_ASYNC] = createEC2AsyncClient()
        }
    }

    private AmazonEC2Client ec2Client(){
        initClients()
        context.session[AWS_CLIENT]
    }

    private AmazonEC2AsyncClient ec2AsyncClient(){
        initClients()
        context.session[AWS_CLIENT_ASYNC]
    }

    private AmazonEC2Client createEC2Client() {
        if (context.session[AWS_CREDENTIALS]) {
            new AmazonEC2Client((AWSCredentials) context.session[AWS_CREDENTIALS])
        } else {
            new AmazonEC2Client()
        }
    }

    private AmazonEC2AsyncClient createEC2AsyncClient() {
        if (context.session[AWS_CREDENTIALS]) {
            new AmazonEC2AsyncClient((AWSCredentials) context.session[AWS_CREDENTIALS])
        } else {
            new AmazonEC2AsyncClient()
        }
    }



    @Usage("Unloads AWS credentials from CommandContext.session")
    @Command
    void unloadCredentials(){
        context.session.remove(AWS_CREDENTIALS)
        out << "Credentials Unloaded"
    }

}
