docker login
sudo systemctl daemon-reload
sudo systemctl restart systemd-resolved
sudo systemctl restart docker
sudo systemctl restart NetworkManager