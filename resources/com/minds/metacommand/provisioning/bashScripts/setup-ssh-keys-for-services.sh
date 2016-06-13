#!/bin/bash

sudo apt-get update -y
sudo apt-get install -y git

sudo touch /etc/ssh/readonly_rsa
sudo touch /etc/ssh/readonly_rsa.pub

#  key providing read only access of repos
sudo echo "-----BEGIN RSA PRIVATE KEY-----" | sudo tee /etc/ssh/readonly_rsa
sudo echo "please provide the text here over and over one line at a time" | sudo tee -a /etc/ssh/readonly_rsa
sudo echo "this is so that the ssh executor can just read one line and push to file" | sudo tee -a /etc/ssh/readonly_rsa
sudo echo "-----END RSA PRIVATE KEY-----" | sudo tee -a /etc/ssh/readonly_rsa

sudo echo "ssh-rsa your key here" | sudo tee /etc/ssh/readonly_rsa.pub

sudo chmod 400 /etc/ssh/readonly_rsa
sudo chmod 400 /etc/ssh/readonly_rsa.pub
sudo echo "Host bitbucket.com" | sudo tee -a /etc/ssh/ssh_config
sudo echo "   IdentityFile /etc/ssh/readonly_rsa" | sudo tee -a /etc/ssh/ssh_config
sudo echo "   StrictHostKeyChecking no" | sudo tee -a /etc/ssh/ssh_config

sudo touch /opt/sshAgentSetup.sh
sudo chmod +x /opt/sshAgentSetup.sh
sudo echo "#!/bin/bash -x" | sudo tee /opt/sshAgentSetup.sh
sudo echo "set -o errexit -o nounset -o pipefail" | sudo tee -a /opt/sshAgentSetup.sh
sudo echo "if [ -z \\\${SSH_AUTH_SOCK+x} ] ; then" | sudo tee -a /opt/sshAgentSetup.sh
sudo echo " eval $(ssh-agent -s)" | sudo tee -a /opt/sshAgentSetup.sh
sudo echo "fi" | sudo tee -a /opt/sshAgentSetup.sh
sudo echo "ssh-add /etc/ssh/readonly_rsa" | sudo tee -a /opt/sshAgentSetup.sh
sudo bash /opt/sshAgentSetup.sh
