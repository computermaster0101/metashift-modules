package com.minds.metacommand.provisioning.servers.common

import com.amazonaws.resources.ec2.*
import com.amazonaws.services.ec2.model.*
import com.amazonaws.services.route53.AmazonRoute53Client
import com.amazonaws.services.route53.model.*
import com.metashift.context.MetaContext
import com.amazonaws.services.ec2.model.Tag

/**
 * FIXME: Update to make modern compared to flintService.groovy
 * Created by nicholaspadilla on 3/27/15.
 */
// default items usually needed
EC2 ec2 = MetaContext.pull(EC2.class)
String creationId = MetaContext.pull('creationId')
Map<String,Object> awsSession = (Map<String,Object>)MetaContext.pull(creationId)
def environmentDomainApex = awsSession.get('environmentDomainApex')
def tags = awsSession.get('tags')
tags << new Tag('Name', "${environmentDomainApex}-CassandraNode-"+creationId)

Vpc activeVpc = awsSession.get('activeVpc')
Subnet activeSubnet = awsSession.get('activeSubnet')
String activeKeyPair = awsSession.get('activeKeyPair')
def route53HostedZoneId = awsSession.get('route53HostedZoneId')
def dataStaxCassandraSubDomain = awsSession.get('dataStaxCassandraSubDomain')

out << 'Active AWS Session Values ->\n'
out << 'activeVpc: '<< blue << activeVpc << reset << '\n'
out << 'activeSubnet: '<< blue << activeSubnet << reset << '\n'
out << 'activeKeyPair: '<< blue << activeKeyPair << reset << '\n'


InstanceType vmInstanceType = InstanceType.M3Large

ImageCollection amis = ec2.getImages(new DescribeImagesRequest().withImageIds("ami-f9a2b690"))

Image baseAmi = null
if(amis.iterator().hasNext()){
    baseAmi = amis.iterator().next()

    out << "Success in finding the base ami!\n"
    out.flush()
}else{
    out << "We failed in finding the base ami!\n"
    out.flush()
}


// Instance Options
//  --clustername <name> The name of the Cassandra cluster REQUIRED
//  --totalnodes <#> Cluster size REQUIRED
//  --version [ community | enterprise ] Installs either DataStax Enterprise or DataStax Community Edition REQUIRED DataStax Enterprise Specific
//  --username <user> The username provided during DSE registration
//  --password is REQUIRED for a DSE installation
//  --password <pass> The password provided during DSE registration
//  --username is REQUIRED for a DSE installation
//  --analyticsnodes <#> Number of analytics nodes that run with Hadoop Default: 0
//  --searchnodes <#> Number of search nodes that run with Solr Default: 0 Basic Options
//  --release <release_version> Allows for the installation of a previous DSE version Example: 1.0.2-1 Default: Ignored
//  --opscenter no Disables the installation of OpsCenter on the cluster Default: yes Advanced Options


//SSH:
//    22: Default SSH port
//DataStax Enterprise Specific:
//    8012: Hadoop Job Tracker client port
//    8983: Portfolio Demo and Solr website port
//    50030: Hadoop Job Tracker website port
//    50060: Hadoop Task Tracker website port
//OpsCenter:
//    8888: OpsCenter website port
//Intranode:
//    Cassandra:
//        1024+: JMX reconnections
//        7000: Cassandra intra-node port
//        7199: Cassandra JMX monitoring port
//        9160: Cassandra client port
//    DataStax Enterprise Specific:
//        9290: Hadoop thrift port OpsCenter:
//        50031: OpsCenter job tracker proxy
//        61620: OpsCenter intra-node monitoring ports
//        61621: OpsCenter agent port


// create security group for this instance.
SecurityGroup dataStaxServerSG = ec2.createSecurityGroup(new CreateSecurityGroupRequest()
                                                                .withVpcId(activeVpc.getId())
                                                                .withGroupName("${environmentDomainApex}-Data Stax Cassandra Cluster-" + new Date().getTime())
                                                                .withDescription("Data Stax Cassandra Cluster"))

Thread.sleep(5 * 1000)

dataStaxServerSG.createTags(tags)

dataStaxServerSG.authorizeIngress(new AuthorizeSecurityGroupIngressRequest()
        .withGroupId(dataStaxServerSG.getId())
        .withIpProtocol("tcp")
        .withFromPort(22)
        .withToPort(22)
        .withCidrIp("0.0.0.0/0"))
// JMX reconnections --> ONLY AVAILABLE TO ITS OWN SG
dataStaxServerSG.authorizeIngress(new AuthorizeSecurityGroupIngressRequest()
        .withGroupId(dataStaxServerSG.getId())
        .withIpProtocol("tcp")
        .withFromPort(1024)
        .withToPort(65535)
        .withCidrIp("0.0.0.0/0"))
//        .withGroupId(dataStaxServerSG.getId()))
// Cassandra intra-node port --> ONLY AVAILABLE TO ITS OWN SG
dataStaxServerSG.authorizeIngress(new AuthorizeSecurityGroupIngressRequest()
        .withGroupId(dataStaxServerSG.getId())
        .withIpProtocol("tcp")
        .withFromPort(7000)
        .withToPort(7000)
        .withCidrIp("0.0.0.0/0"))
//        .withGroupId(dataStaxServerSG.getId()))
// Cassandra JMX monitoring port --> ONLY AVAILABLE TO ITS OWN SG
dataStaxServerSG.authorizeIngress(new AuthorizeSecurityGroupIngressRequest()
        .withGroupId(dataStaxServerSG.getId())
        .withIpProtocol("tcp")
        .withFromPort(7199)
        .withToPort(7199)
        .withCidrIp("0.0.0.0/0"))
//        .withGroupId(dataStaxServerSG.getId()))
// Cassandra client port --> ONLY AVAILABLE TO USER GRID AND ITS OWN SG
dataStaxServerSG.authorizeIngress(new AuthorizeSecurityGroupIngressRequest()
        .withGroupId(dataStaxServerSG.getId())
        .withIpProtocol("tcp")
        .withFromPort(9160)
        .withToPort(9160)
        .withCidrIp("0.0.0.0/0"))
//        .withGroupId(dataStaxServerSG.getId()))
// 8888: OpsCenter website port --> SUPPOSED TO BE ONLY AVAILABLE TO ZUUL
dataStaxServerSG.authorizeIngress(new AuthorizeSecurityGroupIngressRequest()
        .withGroupId(dataStaxServerSG.getId())
        .withIpProtocol("tcp")
        .withFromPort(8888)
        .withToPort(8888)
        .withCidrIp("0.0.0.0/0"))

// create ec2 instance and add the security group
List<Instance> instances = ec2.createInstances(new RunInstancesRequest()
                                                    .withImageId(baseAmi.getId())
                                                    .withInstanceType(vmInstanceType)
                                                    .withKeyName(activeKeyPair)
                                                    .withMinCount(3)
                                                    .withMaxCount(3)
                                                    .withUserData("--clustername UGCassCluster --totalnodes 3 --version community --username ugCassCluster  --password ugCassCluster".bytes.encodeBase64().toString())
                                                    .withPlacement(new Placement().withAvailabilityZone(activeSubnet.getAvailabilityZone()))
                                                    .withNetworkInterfaces(new InstanceNetworkInterfaceSpecification()
                                                        .withAssociatePublicIpAddress(true)
                                                        .withDeviceIndex(0)
                                                        .withSubnetId(activeSubnet.getId())
                                                        .withGroups(dataStaxServerSG.getId()))) // this is for vpc only

Instance firstServer = null
for(Instance i : instances){
    i.createTags(tags)
    if(i.getAmiLaunchIndex() == 0){
        firstServer = i
    }
}
while(firstServer.getState().getName() != "running"){
    out << "Waiting for server state to be running...\n"
    out.flush()
    Thread.sleep(10 * 1000)
    firstServer = ec2.getInstance(firstServer.getId())
}
out << "Success! Data Stax Cassandra Primary running!\n"
out.flush()
firstServer.createTags(tags)

// provide a dns name for the primary server
def client = new AmazonRoute53Client()
def changes = new ArrayList<Change>()
def recourceRecords = new ArrayList<ResourceRecord>()
recourceRecords.add(new ResourceRecord().withValue(firstServer.getPublicDnsName()))
ResourceRecordSet resourceRecordSet = new ResourceRecordSet().withName(dataStaxCassandraSubDomain+".").withType(RRType.CNAME).withResourceRecords(recourceRecords).withTTL(new Long(60))
changes.add(new Change().withAction(ChangeAction.CREATE).withResourceRecordSet(resourceRecordSet))
def changeBatch = new ChangeBatch().withChanges(changes)
def changeResourceRecordSetsRequest = new ChangeResourceRecordSetsRequest().withHostedZoneId(route53HostedZoneId).withChangeBatch(changeBatch)
client.changeResourceRecordSets(changeResourceRecordSetsRequest)
