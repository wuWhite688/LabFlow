@echo off
wsl -d Ubuntu -u root -- docker %*
