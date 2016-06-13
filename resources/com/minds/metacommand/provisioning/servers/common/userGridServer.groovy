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
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient
import com.amazonaws.services.elasticloadbalancing.model.ApplySecurityGroupsToLoadBalancerRequest
import com.amazonaws.services.elasticloadbalancing.model.CreateLoadBalancerRequest
import com.amazonaws.services.elasticloadbalancing.model.Listener
import com.amazonaws.services.elasticloadbalancing.model.RegisterInstancesWithLoadBalancerRequest
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
 * Created by nicholaspadilla on 3/27/15.
 */

// Pull the AWS Session map from the MetaContext
String creationId = (String)MetaContext.pull('creationId')
Map<String,Object> awsSession = (Map<String,Object>)MetaContext.pull(creationId)

// Retrieve required AWS specific details and display them to the user
Vpc activeVpc = (Vpc) awsSession.get('activeVpc')
Subnet activeSubnet = (Subnet) awsSession.get('activeSubnet')
def activeKeyPair = awsSession.get('activeKeyPair')
def route53HostedZoneId = awsSession.get('route53HostedZoneId')
def certificateARNForELB = awsSession.get('certificateARNForELB')
def environmentDomainApex = awsSession.get('environmentDomainApex')
def tags = awsSession.get('tags')
tags << new Tag('Name', "${environmentDomainApex}-UserGridNode-"+creationId)

// Retrieve required clients for aws setup.
EC2 ec2 = MetaContext.pull(EC2.class)
AmazonElasticLoadBalancingClient elbClient = (AmazonElasticLoadBalancingClient) awsSession.get('elbClient')
AmazonRoute53Client route53Client = (AmazonRoute53Client) awsSession.get('route53Client')

// Retrieve required configuration for service.
def sriekInternalSubdomain = awsSession.get('sriekInternalSubdomain')
def userGridELBSubDomain = awsSession.get('userGridELBSubDomain')
def cassandraHosts = awsSession.get('cassandraHosts')
def userGridLoadBalancerUrl = awsSession.get('userGridLoadBalancerUrl')

out << 'Active AWS Session Values ->\n'
out << 'activeVpc: '<< blue << activeVpc << reset << '\n'
out << 'activeSubnet: '<< blue << activeSubnet << reset << '\n'
out << 'activeKeyPair: '<< blue << activeKeyPair << reset << '\n'


InstanceType vmInstanceType = InstanceType.M3Medium
// expects it to be in #!/bin/bash file format.  So figured it would be best to just use a separate file.
File tempDir = new File(BetterBootstrap.TEMP_COMMAND_PATH)
tempDir.mkdirs()
String baseFilePath = '../metashift-modules/resources/com/minds/metacommand/provisioning/'
File shellExecutorTemplate = new File("${baseFilePath}templates/sshExecutor.groovy.gtl")
File installUserGridCommand = new File(tempDir,"installUserGrid.groovy")

def sysadminLoginEmail = "sysadmin@mindsignited.com"
def sysadminLoginPassword = "somepass"
def sysadminEmail = "sysadmin@mindsignited.com"
def managementMailerEmail = "mailer@mindsignited.com"
def jvmArgs = "-server -Xmx3072M -Xms3072M"
def cassandraUsername="ugCassCluster"
def cassandraPassword="ugCassCluster"
def cassStrategyReplicationFactor="2"
def initUserGrid="true" // NOTE: we only ever need to do this once per environment/cassandra db.  So when we add more Nodes, we need to change this.
def cassClusterName="UGCassCluster"


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
SecurityGroup userGridServerSG = ec2.createSecurityGroup(new CreateSecurityGroupRequest()
        .withVpcId(activeVpc.getId())
        .withGroupName("${environmentDomainApex}-UserGridServerSG-" + new Date().getTime())
        .withDescription("User Grid Server SG"))

SecurityGroup userGridServerELBSG = ec2.createSecurityGroup(new CreateSecurityGroupRequest()
        .withVpcId(activeVpc.getId())
        .withGroupName("${environmentDomainApex}-UserGridServerELBSG-" + new Date().getTime())
        .withDescription("User Grid Server ELB SG"))
Thread.sleep(5 * 1000)

userGridServerSG.createTags(tags)
userGridServerELBSG.createTags(tags)

userGridServerSG.authorizeIngress(new AuthorizeSecurityGroupIngressRequest()
        .withGroupId(userGridServerSG.getId())
        .withIpProtocol("tcp")
        .withFromPort(22)
        .withToPort(22)
        .withCidrIp("0.0.0.0/0"))
// SHOULD ONLY BE MADE AVAILABLE TO USER GRID ELB
userGridServerSG.authorizeIngress(new AuthorizeSecurityGroupIngressRequest()
        .withGroupId(userGridServerSG.getId())
        .withIpProtocol("tcp")
        .withFromPort(8080)
        .withToPort(8080)
        .withCidrIp("0.0.0.0/0"))
// INTERNAL ELB --> SHOULD ONLY BE MADE AVAILABLE TO ZUUL AND OTHER INTERNAL SERVICES.
userGridServerELBSG.authorizeIngress(new AuthorizeSecurityGroupIngressRequest()
        .withGroupId(userGridServerSG.getId())
        .withIpProtocol("tcp")
        .withFromPort(8080)
        .withToPort(8080)
        .withCidrIp("0.0.0.0/0"))


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
        .withGroups(userGridServerSG.getId()))) // this is for vpc only

Instance userGridServer = instances.get(0)
userGridServer.createTags(tags)
while(userGridServer.getState().getName() != "running"){
    out << "Waiting for server state to be running...\n"
    out.flush()
    Thread.sleep(10 * 1000)
    userGridServer = ec2.getInstance(userGridServer.getId())
}
out << "Success! User Grid Server running!\n"
out.flush()
userGridServer.createTags(tags)


// create an ELB and attach this server.
def listeners = new ArrayList<Listener>()
listeners.add(new Listener()
        .withProtocol("HTTP")
        .withInstanceProtocol("HTTP")
        .withLoadBalancerPort(8080)
        .withInstancePort(8080))
listeners.add(new Listener()
        .withProtocol("HTTPS")
        .withInstanceProtocol("HTTP")
        .withLoadBalancerPort(443)
        .withInstancePort(8080)
        .withSSLCertificateId(certificateARNForELB))

def elbName = environmentDomainApex.replace(".","-")+"-UserGrid"
def lbRequest = new CreateLoadBalancerRequest()
        .withLoadBalancerName(elbName)
        .withListeners(listeners)
        .withScheme("internal")
        .withSubnets(activeSubnet.getId())

def elb = elbClient.createLoadBalancer(lbRequest)
out << "created load balancer for userGrid\n"
out.flush()


elbClient.applySecurityGroupsToLoadBalancer(new ApplySecurityGroupsToLoadBalancerRequest()
        .withLoadBalancerName(elbName)
        .withSecurityGroups(userGridServerELBSG.getId()))

elbClient.registerInstancesWithLoadBalancer(new RegisterInstancesWithLoadBalancerRequest()
        .withLoadBalancerName(elbName)
        .withInstances(new com.amazonaws.services.elasticloadbalancing.model.Instance(userGridServer.getId())));

/** Need to now install items on server **/
Templater templater = new Templater()
                            .source(shellExecutorTemplate)
                            .put("user", 'ubuntu')
                            .put("host", userGridServer.publicDnsName)

templater.put("commands", ["sudo git archive --remote=git@bitbucket.org:mindsignited/shellbox.git master replicate -o replicate.tar && sudo tar -xf replicate.tar && sudo bash ./replicate git:git@bitbucket.org:mindsignited/shellbox.git " +
                                   "&& sudo bash ./shellbox/java8.sh " +
                                   "&& sudo bash ./shellbox/updateUlimit.sh " +
                                   "&& sudo bash ./shellbox/timezoneAndNtp.sh " +
                                   "&& sudo bash ./shellbox/supervisord.sh " +
                                   "&& sudo bash ./shellbox/installLogstashTopbeat.sh " +
                                                           "--logstashServer \"${sriekInternalSubdomain}\" " +
                                                           "--tag \"usergrid\" " +
                                                           "--serverName \"usergrid\" " +
                                   "&& sudo bash ./shellbox/installUserGrid.sh " +
                                                           "--cassandraHosts \"${cassandraHosts}\" " +
                                                           "--cassandraUsername \"${cassandraUsername}\" " +
                                                           "--cassandraPassword \"${cassandraPassword}\" " +
                                                           "--sysadminLoginEmail \"${sysadminLoginEmail}\" " +
                                                           "--sysadminLoginPassword \"${sysadminLoginPassword}\" " +
                                                           "--sysadminEmail \"${sysadminEmail}\" " +
                                                           "--managementMailerEmail \"${managementMailerEmail}\" " +
                                                           "--userGridLoadBalancerUrl \"${userGridLoadBalancerUrl}\" " +
                                                           "--cassStrategyReplicationFactor \"${cassStrategyReplicationFactor}\" " +
                                                           "--initUserGrid \"${initUserGrid}\" " +
                                                           "--cassClusterName \"${cassClusterName}\" " +
                                                           "--jvmArgs \"${jvmArgs}\"  ;  "])
WritableUtil.write(installUserGridCommand, templater.build())


out << "Installing Services on ${userGridServer.getId()} running servers.\n"
out << blue << "    Installing UserGrid Server..." << reset << "\n"
out.flush()

failedIterations = 0
success = false
while (!success) {
    try {
        installUserGrid()
        success = true;
    } catch (Exception e) {
        out << red << "We encountered an error when running sshoogr ssh connection, installUserGrid. e --> \n${e.getMessage()}" << reset << "\n"
        out.flush()
        Thread.sleep(2 * 1000)

        if (failedIterations < 10) {
            failedIterations++
        }else{
            throw new IllegalStateException("Install UserGrid Script is in error!  SSH Connection to run bash install script has encountered a problem. ")
        }
    }
}

out << blue << "We were able to run connect sshoogr and run installUserGrid!" << reset << "\n"
out.flush()

userGridServer.reboot()
Thread.sleep(10 * 1000) // wait for it to stop or at least be stopping
userGridServer = ec2.getInstance(userGridServer.getId())
while (userGridServer.getState().getName() != "running") {
    out << 'Waiting for server state to be running...\n'
    out.flush()
    Thread.sleep(10 * 1000)
    userGridServer = ec2.getInstance(userGridServer.getId())
}


// provide a dns name for the userGrid elb - auth.domain
def changes = new ArrayList<Change>()
def recourceRecords = new ArrayList<ResourceRecord>()
recourceRecords.add(new ResourceRecord().withValue(elb.getDNSName()))
ResourceRecordSet resourceRecordSet = new ResourceRecordSet().withName(userGridELBSubDomain+".").withType(RRType.CNAME).withResourceRecords(recourceRecords).withTTL(new Long(60))
changes.add(new Change().withAction(ChangeAction.CREATE).withResourceRecordSet(resourceRecordSet))
def changeBatch = new ChangeBatch().withChanges(changes)
def changeResourceRecordSetsRequest = new ChangeResourceRecordSetsRequest().withHostedZoneId(route53HostedZoneId).withChangeBatch(changeBatch)
route53Client.changeResourceRecordSets(changeResourceRecordSetsRequest)