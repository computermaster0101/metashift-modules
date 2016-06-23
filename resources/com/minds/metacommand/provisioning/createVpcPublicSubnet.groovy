package com.minds.metacommand.provisioning

import com.amazonaws.auth.AWSCredentials
import com.amazonaws.regions.Regions
import com.amazonaws.resources.ec2.DhcpOptions
import com.amazonaws.resources.ec2.EC2
import com.amazonaws.resources.ec2.InternetGateway
import com.amazonaws.resources.ec2.RouteTable
import com.amazonaws.resources.ec2.RouteTableCollection
import com.amazonaws.resources.ec2.Subnet
import com.amazonaws.resources.ec2.Vpc
import com.amazonaws.services.ec2.AmazonEC2Client
import com.amazonaws.services.ec2.model.AssociateDhcpOptionsRequest
import com.amazonaws.services.ec2.model.AssociateRouteTableRequest
import com.amazonaws.services.ec2.model.Tag
import com.amazonaws.services.ec2.model.AvailabilityZone
import com.amazonaws.services.ec2.model.CreateDhcpOptionsRequest
import com.amazonaws.services.ec2.model.CreateInternetGatewayRequest
import com.amazonaws.services.ec2.model.CreateRouteRequest
import com.amazonaws.services.ec2.model.CreateRouteTableRequest
import com.amazonaws.services.ec2.model.CreateSecurityGroupRequest
import com.amazonaws.services.ec2.model.CreateSubnetRequest
import com.amazonaws.services.ec2.model.CreateVpcRequest
import com.amazonaws.services.ec2.model.DhcpConfiguration
import com.amazonaws.services.ec2.model.ModifyVpcAttributeRequest
import com.amazonaws.services.ec2.model.AttachInternetGatewayRequest
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient
import com.amazonaws.services.identitymanagement.AmazonIdentityManagementClient
import com.amazonaws.services.identitymanagement.model.GetServerCertificateRequest
import com.amazonaws.services.rds.AmazonRDSClient
import com.amazonaws.services.route53.AmazonRoute53Client
import com.metashift.context.MetaContext
import com.metashift.context.MetaUtil

// default items usually needed
EC2 ec2 = MetaContext.pull(EC2.class)
String creationId = MetaContext.pull('creationId')
Map<String,Object> awsSession = (Map<String,Object>)MetaContext.pull(creationId)
def tags = awsSession.get('tags')
String environmentDomainApex = awsSession.get('environmentDomainApex')
AWSCredentials awsCredentials = MetaContext.pull(AWSCredentials.class)
String region = MetaContext.pull('ec2Region')

out << 'Creating VPC\n'
out.flush()

String dnsSetting = "ec2.internal"
if(Regions.fromName(region) != Regions.US_EAST_1){
    dnsSetting = "${region}.compute.internal"
}
awsSession.put('awsInternalDNSDomain',dnsSetting)

Vpc vpc = ec2.createVpc(MetaContext.pull(CreateVpcRequest.class))
awsSession.put('activeVpc',vpc)

DhcpOptions dhcpOptions = ec2.createDhcpOptions(new CreateDhcpOptionsRequest()
                                                    .withDhcpConfigurations(
                                                    new DhcpConfiguration()
                                                        .withKey("domain-name")
                                                        .withValues(dnsSetting),
                                                    new DhcpConfiguration()
                                                        .withKey("domain-name-servers")
                                                        .withValues("AmazonProvidedDNS")))
tags << new Tag('Name', "${environmentDomainApex}-DhcpOpts-"+creationId)
Thread.sleep(5 * 1000) // sometimes it errors out b/c it says the DHCP opts isn't available. so wait a bit.
dhcpOptions.createTags(tags)
dhcpOptions.associateWithVpc(new AssociateDhcpOptionsRequest().withVpcId(vpc.getId()))


// need to have a default SG for the VPC --> with no rules.
def defaultVPCSG = ec2.createSecurityGroup(new CreateSecurityGroupRequest()
                                                            .withVpcId(vpc.getId())
                                                            .withGroupName("VPC Default")
                                                            .withDescription("Default SG for VPC created on " + new Date().getTime()))
Thread.sleep(5 * 1000) // sometimes it errors out b/c it says the SG isn't available. so wait a bit.
tags << new Tag('Name', "${environmentDomainApex}-DefaultSG-"+creationId)
defaultVPCSG.createTags(tags)
awsSession.put('defaultSecurityGroup',defaultVPCSG)

out << 'Created ' << blue <<'id:'<< vpc.getId() <<' state:' << vpc.getState() << reset << '\n'



out<< 'Modifying Vpc Attributes\n'
out.flush()
Map<String,ModifyVpcAttributeRequest> attributeRequestMap = MetaContext.pullAll(ModifyVpcAttributeRequest.class)
attributeRequestMap.each {String key, ModifyVpcAttributeRequest req ->
    vpc.modifyAttribute(req)
}

out<< 'Tagging\n'
out.flush()
tags << new Tag('Name', "${environmentDomainApex}-VPC-"+creationId)
vpc.createTags(tags)


while(ec2.getVpc(vpc.getId()).getState() != "available"){
    out << 'Waiting for VPC to become available\n'
    out.flush()
   Thread.sleep(10 * 1000)
}

out << 'Creating Subnets\n'
out.flush()
// NOTE: we need to have two subnets per Availability Zone as per MySQL DB's Multi A/Z Deployment (EJBCA)
Subnet subnet = vpc.createSubnet('10.0.1.0/24')
Thread.sleep(5 * 1000) // sometimes it errors out b/c it says the subnet opts isn't available. so wait a bit.
Subnet subnet2 = vpc.createSubnet(new CreateSubnetRequest()
                                        .withVpcId(vpc.getId())
                                        .withCidrBlock('10.0.2.0/24')
                                        .withAvailabilityZone(subnet.getAvailabilityZone()))
Subnet subnet3 = null
Subnet subnet4 = null
AmazonEC2Client amazonEC2 = new AmazonEC2Client(awsCredentials)

List<AvailabilityZone> possibleAvailabilityZones = amazonEC2.describeAvailabilityZones().getAvailabilityZones()
for(AvailabilityZone zone : possibleAvailabilityZones){
    if(zone.state == "available" && !zone.zoneName.equalsIgnoreCase(subnet.getAvailabilityZone())){
        subnet3 = vpc.createSubnet(new CreateSubnetRequest()
                                            .withVpcId(vpc.getId())
                                            .withCidrBlock('10.0.3.0/24')
                                            .withAvailabilityZone(zone.zoneName))
        subnet4 = vpc.createSubnet(new CreateSubnetRequest()
                                            .withVpcId(vpc.getId())
                                            .withCidrBlock('10.0.4.0/24')
                                            .withAvailabilityZone(zone.zoneName))
        break;
    }
}

// needed for db access.
tags << new Tag('Name', "${environmentDomainApex}-Subnet-"+creationId)
subnet.createTags(tags)
tags << new Tag('Name', "${environmentDomainApex}-Subnet2-"+creationId)
subnet2.createTags(tags)
tags << new Tag('Name', "${environmentDomainApex}-Subnet3-"+creationId)
subnet3.createTags(tags)
tags << new Tag('Name', "${environmentDomainApex}-Subnet4-"+creationId)
subnet4.createTags(tags)
out << "   Subnets availabilityZone --> ${subnet.getAvailabilityZone()}\n"
out.flush()

awsSession.put('activeSubnet',subnet)


out << 'Creating Internet Gateway\n'
out.flush()
InternetGateway gateway = ec2.createInternetGateway( MetaContext.pull(CreateInternetGatewayRequest.class) )


out << 'Attaching Internet Gateway to VPC\n'
out.flush()
vpc.attachInternetGateway( new AttachInternetGatewayRequest().withInternetGatewayId(gateway.getId()))

// Getting Default Route Table
RouteTable routeTable = null
RouteTableCollection routeTableCollection = vpc.getRouteTables()
if (routeTableCollection.size() > 0) {
    routeTable = routeTableCollection.first()
}


out << 'Getting / Creating Route Table\n'
out.flush()
if (!routeTable){
    routeTable = vpc.createRouteTable(new CreateRouteTableRequest())
}
tags << new Tag('Name', "${environmentDomainApex}-RouteTable-"+creationId)
routeTable.createTags(tags)

out << 'Associate Route Table With Subnet\n'
out.flush()
routeTable.associateWithSubnet(new AssociateRouteTableRequest().withSubnetId(subnet.getId()))
routeTable.associateWithSubnet(new AssociateRouteTableRequest().withSubnetId(subnet2.getId()))
routeTable.associateWithSubnet(new AssociateRouteTableRequest().withSubnetId(subnet3.getId()))
routeTable.associateWithSubnet(new AssociateRouteTableRequest().withSubnetId(subnet4.getId()))

out << 'Create Route to Internet\n'
out.flush()
routeTable.createRoute(new CreateRouteRequest()
                            .withDestinationCidrBlock('0.0.0.0/0')
                            .withGatewayId(gateway.getId()))


out << blue << 'VPC with Public Subnet Ready' << reset << '\n'


MetaUtil.putAsList(awsSession,'vpc',vpc)

/**
 * Provide RDS Client as we need to update db SG to allow access.
 */

AmazonRDSClient rdsClient = new AmazonRDSClient(awsCredentials)
awsSession.put('rdsClient',rdsClient)

/**
 * Provide Router53 Client as we need to create dns entries
 */
def router53Client = new AmazonRoute53Client(awsCredentials)
awsSession.put('route53Client',router53Client)

/**
 * Provide ELB Client as we need to create elastic load balancers.
 */
AmazonElasticLoadBalancingClient elbClient = new AmazonElasticLoadBalancingClient(awsCredentials)
elbClient.configureRegion(Regions.fromName(region))
awsSession.put('elbClient',elbClient)

/**
 * Provide SSL Cert ARN for ELB's in our infrastructure.
 */
def idmClient = new AmazonIdentityManagementClient()
def result = idmClient.getServerCertificate(new GetServerCertificateRequest()
        .withServerCertificateName((String)awsSession.get('certificateNameForELB')))
awsSession.put('idmClient',idmClient)
awsSession.put('certificateARNForELB',result.getServerCertificate().getServerCertificateMetadata().getArn())


