#!/bin/sh
# run-on-host.sh: run the playbook on the server itself, for a controller
# that can't run Ansible (Windows without WSL).
#
#   ansible/run-on-host.sh [HOST] [BUNDLE] [ansible-playbook options...]
#
#   HOST    an inventory name (the first in inventory.ini unless named)
#   BUNDLE  helper/server-bundle.sh's output (server-bundle.tar.gz in the
#           repo's top folder unless named)
#
# Copies ansible/, the helper's two files and the bundle to ~/ccnix-ansible
# on the host over SSH (the bundle only when it changed), installs
# ansible-core there, and runs site.yml against the host itself.
set -e
here=$(cd "$(dirname "$0")/.." && pwd)
[ -f "$here/ansible/inventory.ini" ] || { echo "no ansible/inventory.ini: copy inventory.example.ini and fill it in" >&2; exit 1; }
name=${1:-$(grep -m1 -E "ansible_host=" "$here/ansible/inventory.ini" | cut -d" " -f1)}; [ $# -gt 0 ] && shift
bundle=${1:-$here/server-bundle.tar.gz}; [ $# -gt 0 ] && shift
[ -f "$bundle" ] || { echo "no bundle at $bundle: helper/server-bundle.sh makes it" >&2; exit 1; }

line=$(grep -E "^$name[[:space:]]" "$here/ansible/inventory.ini") || { echo "$name isn't in inventory.ini" >&2; exit 1; }
addr=$(echo "$line" | sed -n 's/.*ansible_host=\([^ ]*\).*/\1/p')
user=$(echo "$line" | sed -n 's/.*ansible_user=\([^ ]*\).*/\1/p')
dest="$user@$addr"

echo "== to $dest:~/ccnix-ansible"
(cd "$here" && tar -cf - ansible helper/ccnix-tun.c helper/ccnix-tun-setup) |
    ssh "$dest" 'rm -rf ~/ccnix-ansible/ansible ~/ccnix-ansible/helper && mkdir -p ~/ccnix-ansible && tar -xf - -C ~/ccnix-ansible'
local_sum=$(sha256sum "$bundle" | cut -c1-64)
remote_sum=$(ssh "$dest" 'sha256sum ~/ccnix-ansible/server-bundle.tar.gz 2>/dev/null | cut -c1-64')
if [ "$local_sum" != "$remote_sum" ]; then
    echo "== the bundle ($(du -h "$bundle" | cut -f1))"
    scp -q "$bundle" "$dest:ccnix-ansible/server-bundle.tar.gz"
fi

echo "== ansible-playbook on $name"
ssh -t "$dest" "command -v ansible-playbook >/dev/null || sudo apt-get install -y -q ansible-core >/dev/null
    cd ~/ccnix-ansible/ansible && ansible-playbook site.yml -l $name -c local \
        -e ccnix_bundle=\$HOME/ccnix-ansible/server-bundle.tar.gz $*"
