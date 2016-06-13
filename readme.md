# Metashell command help

Add all metashell commands here to use from metashell.

## Convenience
For convenience a `idev` command is available in the core metashell project.
That command will mount directories under this project

Just check out this project next to metashift-cli for idev to work. 

## TODO

We would like to have just one `load` command that takes in a path so 
that you can add various projects into the metashift-cli runtime.  Right 
now it just works with metashift-modules in the configurtion presented. 

NOTE: you should know that after the `idev` command you can interact and 
    debug your groovy scripts.  You can update the script and immediately 
    re-run the script to see your results. 

API Requests welcome. 

## Usage


What you would want to do, with what is provided, is to start up the CLI.
Then issue the `idev` command.  This will load the external projects 
commands into runtime.  Then you will want to call 


flixBegin -> loads environment and special properties for service builders.  

createVpcPublicSubnet -> loads the VPC and other AWS helper/client libs


You should review all commands and groovy files for understanding process 
and execution.  

Once you have done these two commands, you will have a running VPC for which
you can start building servers into.  We provide some standard scripts and
service builders that you can reference.  