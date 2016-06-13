package com.minds.metacommand.provisioning

import com.amazonaws.resources.ec2.EC2
import com.metashift.context.MetaContext
import static groovy.json.JsonOutput.*

// default items usually needed
EC2 ec2 = MetaContext.pull(EC2.class)
String creationId = MetaContext.pull('creationId')
Map<String,Object> awsSession = (Map<String,Object>)MetaContext.pull(creationId)
def tags = awsSession.get('tags')

out << 'AWS Session -> \n' << blue << awsSession.toMapString() << reset << '\n'
