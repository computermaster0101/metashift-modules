package com.minds.metacommand.provisioning

import com.amazonaws.auth.AWSCredentials
import com.amazonaws.regions.Regions
import com.amazonaws.resources.ec2.EC2
import com.amazonaws.resources.ResultCapture
import com.amazonaws.services.ec2.AmazonEC2Client
import com.amazonaws.services.ec2.model.CreateKeyPairResult
import com.amazonaws.services.ec2.model.Filter
import com.amazonaws.services.ec2.model.Tag
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient
import com.amazonaws.services.rds.AmazonRDSClient
import com.amazonaws.services.rds.model.AuthorizeDBSecurityGroupIngressRequest
import com.amazonaws.services.rds.model.DBSecurityGroup
import com.amazonaws.services.rds.model.DescribeDBSecurityGroupsRequest
import com.amazonaws.services.rds.model.DescribeDBSecurityGroupsResult
import com.amazonaws.services.route53.AmazonRoute53Client
import com.metashift.context.MetaContext

// Load commands must come before any beans are put into the context or they will be lost
MetaContext.reset()
MetaContext.load('file:../metashift-modules/**/EC2ProvisioningContext.grvx','file:../metashift-modules/**/DefaultVpcContext.grvx')



String uuid = UUID.randomUUID().toString()
MetaContext.put('creationId',uuid,false)
MetaContext.put(uuid,new HashMap<String,Object>(),false)
Map<String,Object> awsSession = (Map<String,Object>)MetaContext.pull(uuid)
def user = System.getProperty('user.name') ? System.getProperty('user.name') :'metashift'
def tags = [new Tag('createdBy',user),new Tag('createdAt',new Date().toString()),new Tag('creationId',uuid)]
awsSession.put('activeCreationIdFilter',new Filter().withName('tag:creationId').withValues(uuid))
awsSession.put('tags',tags)

// adjusted aws.load so that it adds the creds to the context
// loads our EC2 service client and also adds the current region to context.
//aws.load(Regions.US_WEST_1.getName(), "default")
aws.load(Regions.US_EAST_1.getName(), "default")

// sanity check, all these classes should be available.
EC2 ec2 = MetaContext.pull(EC2.class)
AWSCredentials awsCredentials = MetaContext.pull(AWSCredentials.class)

ResultCapture<CreateKeyPairResult> extractor = new ResultCapture<CreateKeyPairResult>()
ec2.createKeyPair("$user-$uuid", extractor)
def keyPair = extractor.getClientResult().getKeyPair()
// TODO: Tag key pair

File dir = new File("${System.getProperty('user.home')}/.aws/keys")
dir.mkdirs()
File pemFile = new File(dir,"${user}-${uuid}.pem")
out << 'Creating AWS Key at ' << blue << pemFile.canonicalPath << reset << '\n'
pemFile.createNewFile()
pemFile.append(keyPair.getKeyMaterial())

awsSession.put('activeKeyPair',keyPair.getKeyName())
awsSession.put('activeKeyPairPath',pemFile.absoluteFile)

/**
 * Some static properties.
 */

// main attributes
def deployAsProduction = false // provides a way to trigger a production or development deployment

// your Route53 information here.
def environmentDomainApex = "yourdomain.com"
def certificateNameForELB = "nameOfCertInAWS"
def route53HostedZoneId = "yourHostedZoneId"

// domains
def zuulELBSubDomain = "cloud.${environmentDomainApex}"
def userGridELBSubDomain = "auth.${environmentDomainApex}"
def dataStaxCassandraSubDomain = "dscprimary.${environmentDomainApex}"
def turbineSubDomain = "health.${environmentDomainApex}"
def eurekaPeerOneDNS = "discovery.${environmentDomainApex}"
def eurekaPeerTwoDNS = "discovery-peer.${environmentDomainApex}"
def riakELBSubdomain = "store-internal.${environmentDomainApex}"
def rabbitSubDomain = "message.${environmentDomainApex}"
def sriekELBSubdomain = "monitoring.${environmentDomainApex}"
def sriekInternalSubdomain = "monitoring-internal.${environmentDomainApex}"
def gitlabSubdomain = "gitlab.${environmentDomainApex}"


def rabbitPort = 5672
def rabbitUser = "rabbit"
def rabbitPass = "rabbit"
def rabbitGuestPass = "apk49vkQEYinab76KJ"
def cassandraHosts = "${dataStaxCassandraSubDomain}:9160"
def userGridLoadBalancerUrl = "http://${userGridELBSubDomain}:8080"


awsSession.put('environmentDomainApex',environmentDomainApex)
awsSession.put('certificateNameForELB',certificateNameForELB)
awsSession.put('route53HostedZoneId',route53HostedZoneId)

awsSession.put('rabbitSubDomain',rabbitSubDomain)
awsSession.put('rabbitPort',rabbitPort)
awsSession.put('rabbitUser',rabbitUser)
awsSession.put('rabbitPass',rabbitPass)
awsSession.put('rabbitGuestPass',rabbitGuestPass)
awsSession.put('sriekELBSubdomain',sriekELBSubdomain)
awsSession.put('sriekInternalSubdomain',sriekInternalSubdomain)
awsSession.put('gitlabSubdomain',gitlabSubdomain)

awsSession.put('zuulELBSubDomain',zuulELBSubDomain+".")
awsSession.put('userGridELBSubDomain',userGridELBSubDomain+".")
awsSession.put('dataStaxCassandraSubDomain',dataStaxCassandraSubDomain+".")
awsSession.put('rabbitSubDomain',rabbitSubDomain+".")
awsSession.put('turbineSubDomain',turbineSubDomain+".")
awsSession.put('riakELBSubdomain',riakELBSubdomain+".")

awsSession.put('cassandraHosts',cassandraHosts)
awsSession.put('userGridLoadBalancerUrl',userGridLoadBalancerUrl)

awsSession.put('eurekaPeerOneDNS',eurekaPeerOneDNS) // these two are used other places and so no '.' is prepended - it is added where needed
awsSession.put('eurekaPeerTwoDNS',eurekaPeerTwoDNS)

/** GIT MAVEN ENV **/
def repository = (deployAsProduction ? "releases" : "snapshots")
awsSession.put('deployAsProduction',deployAsProduction)
awsSession.put('artifactRepository',repository)
awsSession.put('artifactRepositoryHost',"github.com") // where the git maven repo resides, for projects using wagon-git
awsSession.put('artifactRepositoryOwner',"mindsignited")
awsSession.put('artifactRepositoryName',"mavenrepo")

