package com.minds.metacommand.provisioning

import com.minds.metacommand.provisioning.servers.common.*
import com.minds.metacommand.provisioning.servers.flixEcoSystem.*


def start = new Date()
println start

out << magenta << 'Infrastructure Build Starting' << reset << '\n'
out.flush()

flixBegin()
out << magenta << 'Meta Context Init Finished' << reset << '\n'
out.flush()

createVpcPublicSubnet()

out << magenta << 'createVpcPublicSubnet Finished' << reset << '\n'
out.flush()

configDiscoveryServer()

out << magenta << 'configDiscoveryServer Finished' << reset << '\n'
out.flush()

cassandraDataStackServer()

out << magenta << 'dataStaxCassandra Finished' << reset << '\n'
out.flush()

userGridServer()

out << magenta << 'userGridServer Finished' << reset << '\n'
out.flush()

rabbitServer()

out << magenta << 'rabbitServer Finished' << reset << '\n'
out.flush()

zuulServer()

out << magenta << 'zuulServer Finished' << reset << '\n'
out.flush()

hystrixServer()

out << magenta << 'hystrixServer Finished' << reset << '\n'
out.flush()

turbineServer()

out << magenta << 'turbineServer Finished' << reset << '\n'
out << magenta << 'Infrastructure Build Finished' << reset << '\n'
out.flush()

def end = new Date()
println end

