# ProxyHook

ProxyHook makes it possible to use internal CI tools like Jenkins with publicly hosted source code services like
GitHub, by passing webhooks from public IP addresses to private IP addresses.

## The problem
Your open source project's source code lives on GitHub or GitLab, outside the firewall. Your build server (eg Jenkins)
lives inside your firewall. You want Jenkins to trigger a build when there is a commit, or a new pull request.

GitHub can send webhook notifications when a repo changes. Jenkins can listen for such webhooks to trigger builds when
code is changed. This is really handy for quick continuous integration,
[without polling](http://kohsuke.org/2011/12/01/polling-must-die-triggering-jenkins-builds-from-a-git-hook/), or at
least less polling (webhooks are never 100% reliable).

But you can't post webhooks from the internet to a service running on an intranet, with no public IP address.

## The solution
ProxyHook provides a way of accepting webhooks from an internet service like GitHub, and passing them to an intranet
service.

ProxyHook has two components, ProxyHook Server and ProxyHook Client, which work together.

### ProxyHook Server

The ProxyHook Server is hosted with a public IP address (and ideally a public DNS name), where it waits for webhook
notifications (HTTP POST) from services like GitHub. The server also provides a websocket end-point for the ProxyHook
Client.

You can host it on any public Docker host, but one cheap option is to host it on [Hyper.sh](https://hyper.sh/)
with a 128MB nano-container (S2). Note that a bare container on Hyper won't provide HTTPS/SSL security, and the
proxyhook container is not configured for SSL termination. Without SSL security, you should be careful what content
you pass through webhooks. Notifications from public GitHub projects might be okay, but please use your own judgement.

    hyper run --name proxyhook-server --restart=always --detach --size s2 -p 80:8080 docker.io/zanata/proxyhook-server

With Hyper, you will also need to allocate a floating IP address and attach it to the container to give it that
all-important public IP address. Something like `hyper fip allocate --pick 1` and
`hyper fip attach my.ip.address proxyhook-server`.

Once you have a public IP address, you can give it a DNS name using any means you like. One option is
[Duck DNS](http://www.duckdns.org/).

*to stop*:

    hyper rm -f proxyhook-server


### ProxyHook Client

The ProxyHook Client runs inside your firewall, somewhere it can access your intranet service (eg Jenkins). It
maintains a connection to the server's websocket, and receives a message over the websocket when there is a webhook. It
then POSTs that webhook to the target intranet server.

The client does not tell the internet whether the intranet server returned 200, 404 or 500, or wasn't there at all. The
server only knows whether at least one client received the webhook.

The client does not presently expose any sort of HTTP service. It might expose a simple health page or statistics page
in future.

The client can only deliver webhooks to the preconfigured URLs given on the command line.


You can run it through Docker like this:

    docker run -it docker.io/zanata/proxyhook-client -s ws://myproxyhook-server.duckdns.org/listen -k https://my-jenkins.intranet.example.com/github-webhook

To avoid problems with internal SSL CAs (certificate authorities), you might prefer to run the jar file directly on the target server like this:

    java -Dvertx.disableFileCPResolving=true -Xmx32M -jar /path/to/proxyhook-client-fat.jar -s ws://myproxyhook-server.duckdns.org/listen -k http://localhost:8080/github-webhook/

The client supports multiple `-s` arguments, in case you have multiple ProxyHook Servers for redundancy. It maintains
a websocket connection to all of them, and tries to deliver any webhooks which come through, so make sure your target
intranet service can handle multiple delivery.

The client also supports multiple `-k` arguments, so that you can deliver every webhook to multiple intranet servers.

(Note that the `-s` and `-k` options are in [PR #11](https://github.com/zanata/proxyhook/pull/11), so you might have to
do without server redundancy if that PR hasn't been merged yet. Just put the (single) ws URL first, then the webhook
URLs.)

## Monitoring ProxyHook Server
You can use a service like StatusCake or UptimeRobot to watch the server.

- Monitoring the URL http://myproxyhook-server.duckdns.org/ will tell you if the server is running.
- Monitoring the URL http://myproxyhook-server.duckdns.org/ready will tell you if the server is running and at least one client connected (and probably capable of passing webhooks to your intranet service).

## Making ProxyHook more reliable

You can run multiple servers, and have the client connect to all of them using multiple `-s` arguments. You can also
run multiple clients which deliver to the same intranet service.

In both cases, you will need to ensure that your target intranet service can handle multiple delivery of webhooks.
(Jenkins tends to be okay, as long as you don't have an unconditional build trigger for webhook deliveries. It's better
to have Jenkins check git for any actual changes when a webhook is received.)

## Troubleshooting ProxyHook Server on Hyper
If the ProxyHook Server becomes inaccessible, but `hyper ps` and `hyper logs` show that the server is still running,
you might need to reattach the floating IP address.

1. Get the IP address using `hyper ps -f name=proxyhook-server`
2. Detach using `hyper fip detach proxyhook-server`
3. Reattach using `hyper fip attach my.ip.address proxyhook-server`

You may wish to run two servers if you want more reliable delivery. Sometimes individual Hyper containers seem to act
up or lose connectivity for a while, but having a backup can help. Don't forget to configure the client to connect to
both servers.
