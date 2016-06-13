package com.minds.metacommand.provisioning.templates

/*
 * Template command that will run a series of commands on a remote host using ssh.
 * Expects:
 * String user
 * String keyFilePath
 * String host
 * String[] commands
 *
 */

binding ([
        user:'ubuntu',
        keyFilePath:'doh',
        host:'someHost.test',
        commands:['echo wat','echo "double wat"']
])