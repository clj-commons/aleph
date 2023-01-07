# UDP

A UDP socket can be generated using `(aleph.udp/socket {:port 10001, :broadcast? false})`.  If the `:port` is specified, it will yield a duplex socket which can be used to send and receive messages, which are structured as maps with the following data:

```clj
{:host "example.com"
 :port 10001
 :message ...}
```

Where incoming packets will have a `:message` that is a byte-array, which can be coerced using `byte-streams`, and outgoing packets can be any data which can be coerced to a binary representation.  If no `:port` is specified, the socket can only be used to send messages.

To learn more, [read the documentation](https://github.com/clj-commons/aleph/blob/master/examples/src/aleph/examples/udp.clj).

