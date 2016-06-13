package com.minds.metacommand.provisioning.servers.common

import com.amazonaws.resources.ec2.Vpc
import com.amazonaws.resources.ec2.Subnet
import com.amazonaws.resources.ec2.EC2
import com.amazonaws.resources.ec2.Image
import com.amazonaws.resources.ec2.ImageCollection
import com.amazonaws.resources.ec2.SecurityGroup
import com.amazonaws.resources.ec2.Instance
import com.amazonaws.services.ec2.model.AuthorizeSecurityGroupIngressRequest
import com.amazonaws.services.ec2.model.CreateSecurityGroupRequest
import com.amazonaws.services.ec2.model.DescribeImagesRequest
import com.amazonaws.services.ec2.model.InstanceNetworkInterfaceSpecification
import com.amazonaws.services.ec2.model.InstanceType
import com.amazonaws.services.ec2.model.Placement
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
 * Created by nicholaspadilla on 1/12/16.
 */


// Pull the AWS Session map from the MetaContext
String creationId = MetaContext.pull('creationId')
Map<String,Object> awsSession = (Map<String,Object>)MetaContext.pull(creationId)

// Retrieve required AWS specific details and display them to the user
Vpc activeVpc = (Vpc) awsSession.get('activeVpc')
Subnet activeSubnet = (Subnet) awsSession.get('activeSubnet')
def activeKeyPair = awsSession.get('activeKeyPair')
def activeKeyPairPath = awsSession.get('activeKeyPairPath')
def route53HostedZoneId = awsSession.get('route53HostedZoneId')
def environmentDomainApex = awsSession.get('environmentDomainApex')
def tags = awsSession.get('tags')
tags << new Tag('Name', "${environmentDomainApex}-HaProxyLB-"+creationId)

// Retrieve required clients for aws setup.
EC2 ec2 = MetaContext.pull(EC2.class)
AmazonRoute53Client route53Client = (AmazonRoute53Client) awsSession.get('route53Client')

// Retrieve required configuration for service.
def sriekInternalSubdomain = awsSession.get('sriekInternalSubdomain')
def haproxyLBSubdomain = awsSession.get('haproxySubdomainForGateway')

out << 'Active AWS Session Values ->\n'
out << 'activeVpc: '<< blue << activeVpc << reset << '\n'
out << 'activeSubnet: '<< blue << activeSubnet << reset << '\n'
out << 'activeKeyPair: '<< blue << activeKeyPair << reset << '\n'

// Retrieve base ami
InstanceType vmInstanceType = InstanceType.M3Medium
ImageCollection amis = ec2.getImages((DescribeImagesRequest)MetaContext.pull("describeImagesRequestVivid"))
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
        .withGroupName("${environmentDomainApex}-HaProxyLB-" + new Date().getTime())
        .withDescription("HaProxy"))
serverSG.authorizeIngress(new AuthorizeSecurityGroupIngressRequest()
        .withGroupId(serverSG.getId())
        .withIpProtocol("tcp")
        .withFromPort(22)
        .withToPort(22)
        .withCidrIp("0.0.0.0/0"))
serverSG.authorizeIngress(new AuthorizeSecurityGroupIngressRequest()
        .withGroupId(serverSG.getId())
        .withIpProtocol("tcp")
        .withFromPort(80)
        .withToPort(80)
        .withCidrIp("0.0.0.0/0"))
serverSG.authorizeIngress(new AuthorizeSecurityGroupIngressRequest()
        .withGroupId(serverSG.getId())
        .withIpProtocol("tcp")
        .withFromPort(443)
        .withToPort(443)
        .withCidrIp("0.0.0.0/0"))
serverSG.authorizeIngress(new AuthorizeSecurityGroupIngressRequest()
        .withGroupId(serverSG.getId())
        .withIpProtocol("tcp")
        .withFromPort(22002)
        .withToPort(22002)
        .withCidrIp("0.0.0.0/0"))

serverSG.createTags(tags)

out << "Creating EC2 instances with previously created security groups\n"
out << "    Setting up instance in availability zone -> ${activeSubnet.getAvailabilityZone()}\n"
out.flush()
def instances = ec2.createInstances(new RunInstancesRequest()
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


/** Now we setup the instances **/
File tempDir = new File(BetterBootstrap.TEMP_COMMAND_PATH)
tempDir.mkdirs()

String baseFilePath = '../metashift-modules/resources/com/minds/metacommand/provisioning/'

/** Templates and bash scripts **/
File shellExecutorTemplate = new File("${baseFilePath}templates/sshExecutor.groovy.gtl")

/** The Groovy files that hold our compiled commands/functions **/
File installHaProxyCommand = new File(tempDir,"installHaproxy.groovy")

Templater templater = new Templater()
        .put("user", 'ubuntu')
        .put("keyFilePath", activeKeyPairPath)

int failedIterations = 0
boolean success = false
for(Instance server : instances){
    // ensure server is running before continuing.
    while (server.getState().getName() != "running") {
        out << 'Waiting for server state to be running...\n'
        out.flush()
        Thread.sleep(10 * 1000)
        server = ec2.getInstance(server.getId())
    }

    server.createTags(tags)

    // fixme: only able to handle one proxy server this way.
    if(!awsSession.containsKey('haproxyServer')){
        awsSession.put('haproxyServer',server)
    }

    templater.put("host", server.publicDnsName)

    templater.source(shellExecutorTemplate)
    templater.put("commands", ["sudo git archive --remote=git@bitbucket.org:mindsignited/shellbox.git master replicate -o replicate.tar && sudo tar -xf replicate.tar && sudo bash ./replicate git:git@bitbucket.org:mindsignited/shellbox.git " +
                                       "&& sudo bash ./shellbox/java8.sh " +
                                       "&& sudo bash ./shellbox/updateUlimit.sh " +
                                       "&& sudo bash ./shellbox/timezoneAndNtp.sh " +
                                       "&& sudo bash ./shellbox/installLogstashTopbeat.sh " +
                                                               "--logstashServer \"${sriekInternalSubdomain}\" " +
                                                               "--tag \"haproxy\" " +
                                                               "--serverName \"haproxy\" " +
                                       "&& sudo bash ./shellbox/installHaProxy.sh " +
                                                           "--country \"US\" " +
                                                           "--state \"NM\" " +
                                                           "--city \"Rio Rancho\" " +
                                                           "--organization \"Some Company\" " +
                                                           "--organizationalUnit \"DevOps\" " +
                                                           "--commonName \"${haproxyLBSubdomain}\" ;  "])

    WritableUtil.write(installHaProxyCommand, templater.build())


    out << "Installing HaProxy on server. Please wait...\n"
    out.flush()

    success = false
    failedIterations = 0
    while(!success){
        try {
            installHaproxy()
            success = true;
        }catch(Exception e){
            out << red << "We encountered an error when running sshoogr ssh connection, installHaproxy.  e --> \n${e.getMessage()}" << reset << "\n"
            out.flush()
            Thread.sleep(2 * 1000)

            if (failedIterations < 10) {
                failedIterations++
            }else{
                throw new IllegalStateException("Install HaProxy Build Script is in error!  SSH Connection to run bash install script has encountered a problem. ")
            }
        }
    }

    server.reboot()
    Thread.sleep(10 * 1000) // wait for it to stop or at least be stopping
    server = ec2.getInstance(server.getId())
    while (server.getState().getName() != "running") {
        out << 'Waiting for server state to be running...\n'
        out.flush()
        Thread.sleep(10 * 1000)
        server = ec2.getInstance(server.getId())
    }

    // FIXME: We need to fix this for many ha proxy servers so that we can an incrementing domain scheme.

    def changes = new ArrayList<Change>()
    def recourceRecords = new ArrayList<ResourceRecord>()
    recourceRecords.add(new ResourceRecord().withValue(server.publicDnsName))
    ResourceRecordSet resourceRecordSet = new ResourceRecordSet().withName(haproxyLBSubdomain+".").withType(RRType.CNAME).withResourceRecords(recourceRecords).withTTL(new Long(60))
    changes.add(new Change().withAction(ChangeAction.CREATE).withResourceRecordSet(resourceRecordSet))
    def changeBatch = new ChangeBatch().withChanges(changes)
    def changeResourceRecordSetsRequest = new ChangeResourceRecordSetsRequest().withHostedZoneId(route53HostedZoneId).withChangeBatch(changeBatch)
    route53Client.changeResourceRecordSets(changeResourceRecordSetsRequest)
}

// FIXME: loop over the servers security groups and remove the SSH entry.

awsSession.put('haproxySG',serverSG)