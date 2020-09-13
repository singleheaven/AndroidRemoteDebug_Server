package singleheaven.remotedebug.host;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.freddy.chat.im.MessageType;
import com.freddy.im.protobuf.MessageProtobuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.internal.StringUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Slf4j
class ServerHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        log.info("ServerHandler channelActive()" + ctx.channel().remoteAddress());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        log.info("ServerHandler channelInactive()");
        // 用户断开连接后，移除channel
        ChannelContainer.getInstance().removeChannelIfConnectNoActive(ctx.channel());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        super.exceptionCaught(ctx, cause);
        log.info("ServerHandler exceptionCaught()");
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        super.userEventTriggered(ctx, evt);
        log.info("ServerHandler userEventTriggered()");
    }

    private static final Map<String, String> hostDebugRegistered = new ConcurrentHashMap<>();
    private static final Set<String> deviceDebugRegistered = new CopyOnWriteArraySet<>();

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        MessageProtobuf.Msg message = (MessageProtobuf.Msg) msg;
        log.info(String.format("收到来自客户端[%s]->[%s]的消息:%s",
                message.getHead().getFromId(),
                message.getHead().getToId(),
                message.getHead()));
        int msgType = message.getHead().getMsgType();
        // 握手消息
        if (msgType == MessageType.HANDSHAKE.getMsgType()) {
            /*
            Host 端发送的握手消息，包括自身id（host_id）和Android端id（device_id），
            Server端等待匹配host_id和device_id之后，向各端返回握手成功消息。
             */
            boolean pair_found = false;
            final String fromId = message.getHead().getFromId();
            assert !StringUtil.isNullOrEmpty(fromId);
            JSONObject jsonObj = JSON.parseObject(message.getHead().getExtend());
            String whichSide = jsonObj.getString("side");
            assert !StringUtil.isNullOrEmpty(whichSide);
            JSONObject resp = new JSONObject();
            if (whichSide.equals("host")) {
                String deviceId = jsonObj.getString("device_id");
                assert !StringUtil.isNullOrEmpty(deviceId);
                pair_found = deviceDebugRegistered.contains(deviceId);
                if (!pair_found) {
                    hostDebugRegistered.put(deviceId, fromId);
                } else {
                    resp.put("other_side_id", deviceId);
                }
                resp.put("status", 1);
                // 保存用户通道
                ChannelContainer.getInstance().saveChannel(new NettyChannel(fromId, ctx.channel()));
            } else if (whichSide.equals("device")) {
                pair_found = hostDebugRegistered.containsKey(fromId);
                if (!pair_found) {
                    deviceDebugRegistered.add(fromId);
                } else {
                    resp.put("other_side_id", hostDebugRegistered.get(fromId));
                }
                resp.put("status", 1);
                resp.put("other_side_id", hostDebugRegistered.get(fromId));
                ChannelContainer.getInstance().saveChannel(new NettyChannel(fromId, ctx.channel()));
            } else {
                resp.put("status", -1);
                ChannelContainer.getInstance().removeChannelIfConnectNoActive(ctx.channel());
            }

            if (pair_found) {
                message = message.toBuilder().setHead(message.getHead().toBuilder().setExtend(resp.toString()).build()).build();
                ChannelContainer.getInstance().getActiveChannelByUserId(fromId).getChannel().writeAndFlush(message);

                JSONObject headJsonObject = JSON.parseObject(message.getHead().getExtend());
                // 通知另一端
                if (whichSide.equals("host")) {
                    // 通知device端
                    String deviceId = jsonObj.getString("device_id");
                    headJsonObject.put("other_side_id", fromId);
                    message = message.toBuilder().setHead(message.getHead().toBuilder().setExtend(headJsonObject.toJSONString()).build()).build();
                    ChannelContainer.getInstance().getActiveChannelByUserId(deviceId).getChannel().writeAndFlush(message);
                    deviceDebugRegistered.remove(deviceId);
                    hostDebugRegistered.remove(deviceId);
                } else {
                    // 通知host端
                    String host_id = hostDebugRegistered.remove(fromId);
                    hostDebugRegistered.remove(fromId);
                    headJsonObject.put("other_side_id", fromId);
                    message = message.toBuilder().setHead(message.getHead().toBuilder().setExtend(headJsonObject.toJSONString()).build()).build();
                    ChannelContainer.getInstance().getActiveChannelByUserId(host_id).getChannel().writeAndFlush(message);
                }

            }
        } else if (msgType == MessageType.HEARTBEAT.getMsgType()) {
            // 收到心跳消息，原样返回
            String fromId = message.getHead().getFromId();
            ChannelContainer.getInstance().getActiveChannelByUserId(fromId).getChannel().writeAndFlush(message);
        } else if (msgType == MessageType.SINGLE_CHAT.getMsgType()) {
            // 收到2001或3001消息，返回给客户端消息发送状态报告
            String fromId = message.getHead().getFromId();
            MessageProtobuf.Msg.Builder sentReportMsgBuilder = MessageProtobuf.Msg.newBuilder();
            MessageProtobuf.Head.Builder sentReportHeadBuilder = MessageProtobuf.Head.newBuilder();
            sentReportHeadBuilder.setMsgId(message.getHead().getMsgId());
            sentReportHeadBuilder.setMsgType(MessageType.SERVER_MSG_SENT_STATUS_REPORT.getMsgType());
            sentReportHeadBuilder.setTimestamp(System.currentTimeMillis());
            sentReportHeadBuilder.setStatusReport(1);
            sentReportMsgBuilder.setHead(sentReportHeadBuilder.build());
            ChannelContainer.getInstance().getActiveChannelByUserId(fromId).getChannel().writeAndFlush(sentReportMsgBuilder.build());

            // 同时转发消息到接收方
            String toId = message.getHead().getToId();
            ChannelContainer.getInstance().getActiveChannelByUserId(toId).getChannel().writeAndFlush(message);
        }
    }
}
