#!/usr/bin/env python3

# Adapted for Aleph test suite from https://gist.github.com/tsaarni/e075b1e23bf6a110391426a5f7183c6f

#
# This script can be used as a server when you need to test the handling of
# TCP connection establishment timeouts of your client.
#
# The protocol can be HTTP, HTTPS or just about anything else, since connection
# will never be established.  It will hang in TCP handshake.
#

import socket
import sys

port = int(sys.argv[1])

# Bind to server port
s = socket.socket()
s.bind(("", port))
s.listen(0)

# Connect one client to fill in the listen queue
c = socket.socket()
c.connect(("127.0.0.1", port))

# At this point, testing the client connect timeout handling can take place.
# Any attempt to establish connection from external clients will not complete.
# TCP tree-way handshake cannot be completed because TCP implementation at the
# server will not send SYN+ACK back until there is room in the listen queue.

input("X")
