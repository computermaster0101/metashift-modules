package com.minds.metacommand.provisioning.servers.common

import com.amazonaws.resources.ec2.Vpc
import com.amazonaws.resources.ec2.Subnet
import com.amazonaws.resources.ec2.EC2
import com.amazonaws.resources.ec2.Image
import com.amazonaws.resources.ec2.ImageCollection
import com.amazonaws.resources.ec2.SecurityGroup
import com.amazonaws.resources.ec2.Instance
import com.amazonaws.services.ec2.model.*
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient
import com.amazonaws.services.elasticloadbalancing.model.ApplySecurityGroupsToLoadBalancerRequest
import com.amazonaws.services.elasticloadbalancing.model.CreateLoadBalancerRequest
import com.amazonaws.services.elasticloadbalancing.model.Listener
import com.amazonaws.services.elasticloadbalancing.model.RegisterInstancesWithLoadBalancerRequest
import com.amazonaws.services.route53.AmazonRoute53Client
import com.amazonaws.services.route53.model.*
import com.metashift.context.MetaContext
import com.metashift.core.util.FileUtil
import com.metashift.core.util.WritableUtil
import com.metashift.crash.BetterBootstrap
import com.metashift.template.Templater
import com.amazonaws.services.ec2.model.Tag

/**
 * Created by Michael Herber II on 10/14/15.
 */


// Pull the AWS Session map from the MetaContext
String creationId = (String)MetaContext.pull('creationId')
Map<String,Object> awsSession = (Map<String,Object>)MetaContext.pull(creationId)

// Retrieve required AWS specific details and display them to the user
Vpc activeVpc = (Vpc) awsSession.get('activeVpc')
Subnet activeSubnet = (Subnet) awsSession.get('activeSubnet')
def activeKeyPair = awsSession.get('activeKeyPair')
def activeKeyPairPath = awsSession.get('activeKeyPairPath')
def route53HostedZoneId = awsSession.get('route53HostedZoneId')
def certificateARNForELB = awsSession.get('certificateARNForELB')
def environmentDomainApex = awsSession.get('environmentDomainApex')
def tags = awsSession.get('tags')
tags << new Tag('Name', "${environmentDomainApex}-RiakNode-"+creationId)

// Retrieve required clients for aws setup.
EC2 ec2 = MetaContext.pull(EC2.class)
AmazonElasticLoadBalancingClient elbClient = (AmazonElasticLoadBalancingClient) awsSession.get('elbClient')
AmazonRoute53Client route53Client = (AmazonRoute53Client) awsSession.get('route53Client')

// Retrieve required configuration for service.
def sriekInternalSubdomain = awsSession.get('sriekInternalSubdomain')
def riakELBSubdomain = awsSession.get('riakELBSubdomain')

out << 'Active AWS Session Values ->\n'
out << 'activeVpc: '<< blue << activeVpc << reset << '\n'
out << 'activeSubnet: '<< blue << activeSubnet << reset << '\n'
out << 'activeKeyPair: '<< blue << activeKeyPair << reset << '\n'
out << 'riakELBSubdomain: ' << blue <<riakELBSubdomain << reset << '\n'


// Retrieve base ami
InstanceType vmInstanceType = InstanceType.M3Medium
ImageCollection amis = ec2.getImages((DescribeImagesRequest)MetaContext.pull("describeImagesRequestTrusty"))
Image baseAmi = null
if(amis.iterator().hasNext()){
    baseAmi = amis.iterator().next()

    out << "Success in finding the base ami!\n"
    out.flush()
}else{
    out << "We failed in finding the base ami!\n"
    out.flush()
}


// create security group for this instance.
SecurityGroup serverSG = ec2.createSecurityGroup(new CreateSecurityGroupRequest()
                                                            .withVpcId(activeVpc.getId())
                                                            .withGroupName("${environmentDomainApex}-RiakServer-" + new Date().getTime())
                                                            .withDescription("Riak Server SG"))
// NOTE: 22 is the port that we will use for system connectivity to the nodes
serverSG.authorizeIngress(new AuthorizeSecurityGroupIngressRequest()
                                .withGroupId(serverSG.getId())
                                .withIpProtocol("tcp")
                                .withFromPort(22)
                                .withToPort(22)
                                .withCidrIp("0.0.0.0/0"))
// NOTE: 8087 is the port that we will use for system connectivity to the nodes
// NOTE: xxxx is the port that we will use for system connectivity to the nodes
serverSG.authorizeIngress(new AuthorizeSecurityGroupIngressRequest()
                                .withGroupId(serverSG.getId())
                                .withIpPermissions(new IpPermission()
                                                        .withIpProtocol("tcp")
                                                        .withFromPort(0)
                                                        .withToPort(65535)
                                                        .withUserIdGroupPairs(new UserIdGroupPair()
                                                                                    .withGroupId(serverSG.getId()))))
// Note: this is the security group for the load balancer
SecurityGroup riakClusterELBSG = ec2.createSecurityGroup(new CreateSecurityGroupRequest()
                                                                    .withVpcId(activeVpc.getId())
                                                                    .withGroupName("${environmentDomainApex}-RiakClusterELB-" + new Date().getTime())
                                                                    .withDescription("Riak Cluster ELB SG"))

Thread.sleep(5 * 1000)

// open up ports from ELB to Cluster instances.
serverSG.authorizeIngress(new AuthorizeSecurityGroupIngressRequest()
                                .withGroupId(serverSG.getId())
                                .withIpPermissions(new IpPermission()
                                                        .withIpProtocol("tcp")
                                                        .withFromPort(0)
                                                        .withToPort(65535)
                                                        .withUserIdGroupPairs(new UserIdGroupPair()
                                                                                    .withGroupId(riakClusterELBSG.getId()))))

serverSG.createTags(tags)
riakClusterELBSG.createTags(tags)



//create the ec2 instances. Since a riak cluster requires no less that 5 nodes, this will always create 5 instances.
out << "Creating EC2 instances with previously created security groups\n"
out << "    Setting up instance in availability zone -> ${activeSubnet.getAvailabilityZone()}\n"
out.flush()
def instances = ec2.createInstances(new RunInstancesRequest()
                                                   .withImageId(baseAmi.getId())
                                                   .withInstanceType(vmInstanceType)
                                                   .withKeyName(activeKeyPair)
                                                   .withMinCount(5)
                                                   .withMaxCount(5)
                                                   .withPlacement(new Placement().withAvailabilityZone(activeSubnet.getAvailabilityZone()))
                                                   .withNetworkInterfaces(new InstanceNetworkInterfaceSpecification()
                                                   .withAssociatePublicIpAddress(true)
                                                   .withDeviceIndex(0)
                                                   .withSubnetId(activeSubnet.getId())
                                                   .withGroups(serverSG.getId()))) // this is for vpc only


def listeners = new ArrayList<Listener>()
listeners.add(new Listener()
        .withProtocol("HTTP")
        .withInstanceProtocol("HTTP")
        .withLoadBalancerPort(8087)
        .withInstancePort(8087))

listeners.add(new Listener()
        .withProtocol("HTTP")
        .withInstanceProtocol("HTTP")
        .withLoadBalancerPort(8985)
        .withInstancePort(8985))

def elbName = environmentDomainApex.replace(".","-")+"-RiakCluster"
def lbRequest = new CreateLoadBalancerRequest()
                        .withLoadBalancerName(elbName)
                        .withListeners(listeners)
                        .withScheme("internal")
                        .withSubnets(activeSubnet.getId())

def elb = elbClient.createLoadBalancer(lbRequest);
out << "created load balancer for Riak Cluster\n"
out.flush()

elbClient.applySecurityGroupsToLoadBalancer(new ApplySecurityGroupsToLoadBalancerRequest()
                                                    .withLoadBalancerName(elbName)
                                                    .withSecurityGroups(riakClusterELBSG.getId()))

/** Now we setup the instances **/
File tempDir = new File(BetterBootstrap.TEMP_COMMAND_PATH)
tempDir.mkdirs()

String baseFilePath = '../metashift-modules/resources/com/minds/metacommand/provisioning/'

/** Templates and bash scripts **/
File shellExecutorTemplate = new File("${baseFilePath}templates/sshExecutor.groovy.gtl")

/** The Groovy files that hold our compiled commands/functions **/
File installRiakCommand = new File(tempDir,"installRiak.groovy")
File riakJoinClusterCommand = new File(tempDir,"riakJoinCluster.groovy")

Templater templater = new Templater()
        .put("user", 'ubuntu')
        .put("keyFilePath", activeKeyPairPath)

def  previous = ""
int failedIterations
boolean success
for(Instance server : instances){
    // ensure server is running before continuing.
    while (server.getState().getName() != "running") {
        out << 'Waiting for server state to be running...\n'
        out.flush()
        Thread.sleep(10 * 1000)
        server = ec2.getInstance(server.getId())
    }
    server.createTags(tags)
    // We have to do this first so that we can mount this guy into the right riak directory for data.
    // this has to be done before actually installing riak
    com.amazonaws.resources.ec2.Image ebsImage = ec2.createVolume(new CreateVolumeRequest()
                                                                        .withAvailabilityZone(activeSubnet.getAvailabilityZone())
                                                                        .withEncrypted(false)
    /**
     * Only valid for Provisioned IOPS (SSD) volumes. The number of I/O
     * operations per second (IOPS) to provision for the volume.
     */
                                                                        //.withIops(1000)
    /**
     * The size of the volume, in GiBs. <p>Constraints: If the volume type is
     * <code>io1</code>, the minimum size of the volume is 4 GiB; otherwise,
     * the minimum size is 1 GiB. The maximum volume size is 1024 GiB. If you
     * specify a snapshot, the volume size must be equal to or larger than
     * the snapshot size. <p>Default: If you're creating the volume from a
     * snapshot and don't specify a volume size, the default is the snapshot
     * size.
     */
                                                                        .withSize(5) // FIXME: Fix for production. should be 1024 or something.
    /**
     * The volume type. This can be <code>gp2</code> for General Purpose
     * (SSD) volumes, <code>io1</code> for Provisioned IOPS (SSD) volumes, or
     * <code>standard</code> for Magnetic volumes. <p>Default:
     * <code>standard</code>
     * <p>
     * <b>Constraints:</b><br/>
     * <b>Allowed Values: </b>standard, io1, gp2
     */
                                                                        .withVolumeType(VolumeType.Gp2)) // FIXME: Fix for production - should we use Io1?


    // stop instance so we can add the ebs storage
    server.stop()
    while (server.getState().getName() != "stopped") {
        out << 'Waiting for server state to be stopped...\n'
        out.flush()
        Thread.sleep(10 * 1000)
        server = ec2.getInstance(server.getId())
    }
    // now attach the ebs storage
    server.attachVolume(new AttachVolumeRequest()
                                .withInstanceId(server.getId())
                                .withVolumeId(ebsImage.getId())
                                .withDevice("/dev/xvdf"))
    server.start()
    while (server.getState().getName() != "running") {
        out << 'Waiting for server state to be running...\n'
        out.flush()
        Thread.sleep(10 * 1000)
        server = ec2.getInstance(server.getId())
    }

    elbClient.registerInstancesWithLoadBalancer(new RegisterInstancesWithLoadBalancerRequest()
                                                        .withLoadBalancerName(elbName)
                                                        .withInstances(new com.amazonaws.services.elasticloadbalancing.model.Instance(server.getId())));

    templater.put("host", server.publicDnsName)

    templater.source(shellExecutorTemplate)
    templater.put("commands", ["sudo git archive --remote=git@bitbucket.org:mindsignited/shellbox.git master replicate -o replicate.tar && sudo tar -xf replicate.tar && sudo bash ./replicate git:git@bitbucket.org:mindsignited/shellbox.git " +
                                       "&& sudo bash ./shellbox/java8.sh " +
                                       "&& sudo bash ./shellbox/updateUlimit.sh " +
                                       "&& sudo bash ./shellbox/timezoneAndNtp.sh " +
                                       "&& sudo bash ./shellbox/installLogstashTopbeat.sh " +
                                                               "--logstashServer \"${sriekInternalSubdomain}\" " +
                                                               "--tag \"riak\" " +
                                                               "--serverName \"riak\" " +
                                       "&& sudo bash ./shellbox/installRiak.sh --nodename \"riak@${server.privateDnsName}\""])
    WritableUtil.write(installRiakCommand, templater.build())

    out << "Installing Riak on server. Please wait...\n"
    out.flush()

    success = false
    failedIterations = 0
    while(!success){
        try {
            installRiak()
            success = true;
        }catch(Exception e){
            out << red << "We encountered an error when running sshoogr ssh connection, installRiak.  e --> \n${e.getMessage()}" << reset << "\n"
            out.flush()
            Thread.sleep(2 * 1000)

            if (failedIterations < 10) {
                failedIterations++
            }else{
                throw new IllegalStateException("Install Riak Build Script is in error!  SSH Connection to run bash install script has encountered a problem. ")
            }
        }
    }

    // we need to restart the server so that we can ensure all system settings are taken
    // When it comes back up we should have command `ulimit -a` provide open files of 65536 and our mount.
    server.reboot()
    Thread.sleep(10 * 1000) // wait for it to stop or at least be stopping
    server = ec2.getInstance(server.getId())
    while (server.getState().getName() != "running") {
        out << 'Waiting for server state to be running...\n'
        out.flush()
        Thread.sleep(10 * 1000)
        server = ec2.getInstance(server.getId())
    }

    if(!previous.isEmpty()) {
        success = false
        failedIterations = 0
        while (!success) {
            try {

                templater.source(shellExecutorTemplate)
                templater.put("commands", ["sudo riak-admin cluster join \"riak@${previous}\""])
                WritableUtil.write(riakJoinClusterCommand, templater.build())

                riakJoinCluster()
                success = true;
            }catch(Exception e){
                out << red << "We encountered an error when running sshoogr ssh connection, riakJoinCluster.  e --> \n${e.getMessage()}" << reset << "\n"
                out.flush()
                Thread.sleep(2 * 1000)

                if (failedIterations < 10) {
                    failedIterations++
                }else{
                    throw new IllegalStateException("Riak Join Cluster Build Script is in error!  SSH Connection to run bash install script has encountered a problem. ")
                }
            }
        }
    }

    previous = server.getPrivateDnsName()
}

// FIXME: loop over the servers security groups and remove the SSH entry.

def changes = new ArrayList<Change>()
def recourceRecords = new ArrayList<ResourceRecord>()
recourceRecords.add(new ResourceRecord().withValue(elb.getDNSName()))
ResourceRecordSet resourceRecordSet = new ResourceRecordSet().withName(riakELBSubdomain+".").withType(RRType.CNAME).withResourceRecords(recourceRecords).withTTL(new Long(60))
changes.add(new Change().withAction(ChangeAction.CREATE).withResourceRecordSet(resourceRecordSet))
def changeBatch = new ChangeBatch().withChanges(changes)
def changeResourceRecordSetsRequest = new ChangeResourceRecordSetsRequest().withHostedZoneId(route53HostedZoneId).withChangeBatch(changeBatch)
route53Client.changeResourceRecordSets(changeResourceRecordSetsRequest)
