package com.minds.metacommand.provisioning.servers.common

import com.amazonaws.resources.ec2.Vpc
import com.amazonaws.resources.ec2.Subnet
import com.amazonaws.resources.ec2.EC2
import com.amazonaws.resources.ec2.Image
import com.amazonaws.resources.ec2.ImageCollection
import com.amazonaws.resources.ec2.SecurityGroup
import com.amazonaws.resources.ec2.Instance
import com.amazonaws.services.ec2.model.InstanceNetworkInterfaceSpecification
import com.amazonaws.services.ec2.model.Placement
import com.amazonaws.services.ec2.model.AuthorizeSecurityGroupIngressRequest
import com.amazonaws.services.ec2.model.CreateSecurityGroupRequest
import com.amazonaws.services.ec2.model.DescribeImagesRequest
import com.amazonaws.services.ec2.model.InstanceType
import com.amazonaws.services.ec2.model.RunInstancesRequest
import com.amazonaws.services.ec2.model.Tag
import com.amazonaws.services.route53.AmazonRoute53Client
import com.amazonaws.services.route53.model.Change
import com.amazonaws.services.route53.model.ChangeAction
import com.amazonaws.services.route53.model.ChangeBatch
import com.amazonaws.services.route53.model.ChangeResourceRecordSetsRequest
import com.amazonaws.services.route53.model.RRType
import com.amazonaws.services.route53.model.ResourceRecord
import com.amazonaws.services.route53.model.ResourceRecordSet
import com.metashift.context.MetaContext
import com.metashift.core.util.FileUtil
import com.metashift.core.util.WritableUtil
import com.metashift.crash.BetterBootstrap
import com.metashift.template.Templater


/**
 * Created by nicholaspadilla on 3/19/15.
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
def environmentDomainApex = awsSession.get('environmentDomainApex')
def tags = awsSession.get('tags')
tags << new Tag('Name', "${environmentDomainApex}-RabbitMQNode-"+creationId)

// Retrieve required clients for aws setup.
EC2 ec2 = MetaContext.pull(EC2.class)
AmazonRoute53Client route53Client = (AmazonRoute53Client) awsSession.get('route53Client')

// Retrieve required configuration for service.
def sriekInternalSubdomain = awsSession.get('sriekInternalSubdomain')
def rabbitSubDomain = awsSession.get 'rabbitSubDomain'
def rabbitGuestPass = awsSession.get 'rabbitGuestPass'
def rabbitUser = awsSession.get 'rabbitUser'
def rabbitPass = awsSession.get 'rabbitPass'

out << 'Active AWS Session Values ->\n'
out << 'activeVpc: '<< blue << activeVpc << reset << '\n'
out << 'activeSubnet: '<< blue << activeSubnet << reset << '\n'
out << 'activeKeyPair: '<< blue << activeKeyPair << reset << '\n'
out << 'activeKeyPairPath: '<< blue << activeKeyPairPath << reset << '\n'
out << 'rabbitSubDomain: '<< blue << rabbitSubDomain << reset << '\n'

// File used
File tempDir = new File(BetterBootstrap.TEMP_COMMAND_PATH)
tempDir.mkdirs()
String baseFilePath = '../metashift-modules/resources/com/minds/metacommand/provisioning/'
File shellExecutorTemplate = new File("${baseFilePath}templates/sshExecutor.groovy.gtl")
File getErlangCookieTemplate = new File("${baseFilePath}templates/getErlangCookie.groovy.gtl")

File erlangCookieCommand = new File(tempDir,"getErlangCookie.groovy")
File installRabbitMQCommand = new File(tempDir,"installRabbitMQ.groovy")

InstanceType vmInstanceType = InstanceType.M3Medium

ImageCollection amis = ec2.getImages((DescribeImagesRequest)MetaContext.pull("describeImagesRequestVivid"))
Image baseAmi = null
if(amis.iterator().hasNext()){
    baseAmi = amis.iterator().next()

    out << 'Success in finding the base ami!\n'
    out.flush()
}else{
    out << 'We failed in finding the base ami!\n'
    out.flush()
}



// create security group for this instance.
SecurityGroup serverSG = ec2.createSecurityGroup(new CreateSecurityGroupRequest()
                                                     .withVpcId(activeVpc.getId())
                                                     .withGroupName("${environmentDomainApex}-RabbitServer-" + new Date().getTime())
                                                     .withDescription('Rabbit Server SG'))
Thread.sleep(5 * 1000)

serverSG.createTags(tags)

// rabbitmq sg needs -> 5672 for everyone(default rabbitmq) ; 15672 for everyone(web) ; 61613 for everyone(stomp) ;
serverSG.authorizeIngress(new AuthorizeSecurityGroupIngressRequest()
                              .withGroupId(serverSG.getId())
                              .withIpProtocol('tcp')
                              .withFromPort(22)
                              .withToPort(22)
                              .withCidrIp('0.0.0.0/0'))
serverSG.authorizeIngress(new AuthorizeSecurityGroupIngressRequest()
                              .withGroupId(serverSG.getId())
                              .withIpProtocol('tcp')
                              .withFromPort(5672)
                              .withToPort(5672)
                              .withCidrIp('0.0.0.0/0'))
serverSG.authorizeIngress(new AuthorizeSecurityGroupIngressRequest()
                              .withGroupId(serverSG.getId())
                              .withIpProtocol('tcp')
                              .withFromPort(15672)
                              .withToPort(15672)
                              .withCidrIp('0.0.0.0/0'))
serverSG.authorizeIngress(new AuthorizeSecurityGroupIngressRequest()
                              .withGroupId(serverSG.getId())
                              .withIpProtocol('tcp')
                              .withFromPort(61613)
                              .withToPort(61613)
                              .withCidrIp('0.0.0.0/0'))


// create ec2 instance and add the security group
List<Instance> instances = ec2.createInstances(new RunInstancesRequest()
                                                   .withImageId(baseAmi.getId())
                                                   .withInstanceType(vmInstanceType)
                                                   .withKeyName(activeKeyPair)
                                                   .withMinCount(1)
                                                   .withMaxCount(1)
                                                   .withPlacement(new Placement().withAvailabilityZone(activeSubnet.getAvailabilityZone()))
                                                   .withNetworkInterfaces(new InstanceNetworkInterfaceSpecification()
                                                   .withAssociatePublicIpAddress(true)
                                                   .withDeviceIndex(0)
                                                   .withSubnetId(activeSubnet.getId())
                                                   .withGroups(serverSG.getId()))) // this is for vpc only

Instance server = instances.get(0)
server.createTags(tags)
while(server.getState().getName() != 'running'){
    out << 'Waiting for server state to be running...\n'
    out.flush()
    Thread.sleep(10 * 1000)
    server = ec2.getInstance(server.getId())
}
out << 'Rabbit Server running!\n'
out.flush()
server.createTags(tags)



out << 'Attempting to run build on server...\n'
out.flush()


Templater templater = new Templater()
                            .source(shellExecutorTemplate)
                            .put("user", 'ubuntu')
                            .put("host", server.publicDnsName)
                            .put("keyFilePath", activeKeyPairPath)


templater.source(shellExecutorTemplate)
templater.put("commands", ["sudo git archive --remote=git@bitbucket.org:mindsignited/shellbox.git master replicate -o replicate.tar && sudo tar -xf replicate.tar && sudo bash ./replicate git:git@bitbucket.org:mindsignited/shellbox.git " +
                                   "&& sudo bash ./shellbox/java8.sh " +
                                   "&& sudo bash ./shellbox/updateUlimit.sh " +
                                   "&& sudo bash ./shellbox/timezoneAndNtp.sh " +
                                   "&& sudo bash ./shellbox/installLogstashTopbeat.sh " +
                                                           "--logstashServer \"${sriekInternalSubdomain}\" " +
                                                           "--tag \"rabbitmq\" " +
                                                           "--serverName \"rabbitmq\" " +
                                   "&& sudo bash ./shellbox/installRabbitmq.sh " +
                                                       "--adminUsername \"${rabbitUser}\" " +
                                                       "--adminPassword \"${rabbitPass}\" " +
                                                       "--changeGuestPasswordTo \"${rabbitGuestPass}\"  ;  "])

WritableUtil.write(installRabbitMQCommand, templater.build())


templater.source(getErlangCookieTemplate)
        .put("creationId", creationId)
WritableUtil.write(erlangCookieCommand, templater.build())

out << "Installing Services on ${server.getId()} running server.\n"
out << blue << "Installing RabbitMQ Server..." << reset << "\n"
out.flush()

failedIterations = 0
success = false
while (!success) {
    try {
        installRabbitMQ()
        success = true;
    } catch (Exception e) {
        out << red << "We encountered an error when running sshoogr ssh connection, installRabbitMQ. e --> \n${e.getMessage()}" << reset << "\n"
        out.flush()
        Thread.sleep(2 * 1000)

        if (failedIterations < 10) {
            failedIterations++
        }else{
            throw new IllegalStateException("Install RabbitMQ Script is in error!  SSH Connection to run bash install script has encountered a problem. ")
        }
    }
}

out << blue << "We were able to run connect sshoogr and run installRabbitMQ!" << reset << "\n"
out << blue << "Installing the Erlang Cookie..." << reset << "\n"
out.flush()

failedIterations = 0
success = false
while (!success) {
    try {
        getErlangCookie()
        success = true;
    } catch (Exception e) {
        out << red << "We encountered an error when running sshoogr ssh connection, getErlangCookie. e --> \n${e.getMessage()}" << reset << "\n"
        out.flush()
        Thread.sleep(2 * 1000)

        if (failedIterations < 10) {
            failedIterations++
        }else{
            throw new IllegalStateException("Setup Erlang Cookie Script is in error!  SSH Connection to run bash install script has encountered a problem. ")
        }
    }
}

out << blue << "We were able to run connect sshoogr and run getErlangCookie!" << reset << "\n"
out.flush()

server.reboot()
Thread.sleep(10 * 1000) // wait for it to stop or at least be stopping
server = ec2.getInstance(server.getId())
while (server.getState().getName() != "running") {
    out << 'Waiting for server state to be running...\n'
    out.flush()
    Thread.sleep(10 * 1000)
    server = ec2.getInstance(server.getId())
}

out << 'Success! Installed Rabbits..\n'
out.flush()


def changes = new ArrayList<Change>()
def recourceRecords = new ArrayList<ResourceRecord>()
recourceRecords.add(new ResourceRecord().withValue(server.getPublicDnsName()))
ResourceRecordSet resourceRecordSet = new ResourceRecordSet().withName(rabbitSubDomain+".").withType(RRType.CNAME).withResourceRecords(recourceRecords).withTTL(new Long(60))
changes.add(new Change().withAction(ChangeAction.CREATE).withResourceRecordSet(resourceRecordSet))
def changeBatch = new ChangeBatch().withChanges(changes)
def changeResourceRecordSetsRequest = new ChangeResourceRecordSetsRequest().withHostedZoneId(route53HostedZoneId).withChangeBatch(changeBatch)
route53Client.changeResourceRecordSets(changeResourceRecordSetsRequest)

/** We are done with provisioning, so lets ensure downstream deployments have rabbitmqSg available **/
awsSession.put('rabbitmqSg',serverSG)
