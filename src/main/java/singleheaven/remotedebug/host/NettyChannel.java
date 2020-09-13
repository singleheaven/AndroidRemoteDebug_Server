package singleheaven.remotedebug.host;

import io.netty.channel.Channel;

public class NettyChannel {
    private String userId;
    private Channel channel;

    public NettyChannel(String userId, Channel channel) {
        this.userId = userId;
        this.channel = channel;
    }

    public String getChannelId() {
        return channel.id().toString();
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Channel getChannel() {
        return channel;
    }

    public void setChannel(Channel channel) {
        this.channel = channel;
    }

    public boolean isActive() {
        return channel.isActive();
    }
}
