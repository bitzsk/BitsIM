package protocol.process;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import model.Friendship;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import protocol.MqttMesageFactory;
import protocol.message.*;
import protocol.message.ConnAckMessage.ConnectionStatus;
import protocol.process.interfaces.impl.IdentityAuthenticator;
import protocol.process.interfaces.impl.MapDBPersistentStore;
import protocol.process.interfaces.IAuthenticator;
import protocol.process.interfaces.IMessageStore;
import protocol.process.interfaces.ISessionStore;
import protocol.process.event.PubRelEvent;
import protocol.process.event.PublishEvent;
import protocol.process.event.job.RePubRelJob;
import protocol.process.event.job.RePublishJob;
import protocol.process.subscribe.SubscribeStore;
import protocol.process.subscribe.Subscription;

import service.*;
import util.QuartzManager;
import util.StringTool;

/**
 * 协议所有的业务处理都在此类，注释中所指协议为MQTT3.3.1协议英文版
 */
public class ProtocolProcess {
    private final static Logger logger = LoggerFactory.getLogger(ProtocolProcess.class);

    // 客户端连接映射表
    private ConcurrentHashMap<Object, ConnectionDescriptor> clients = new ConcurrentHashMap<>();
    // 存储遗嘱信息，通过ID映射遗嘱信息
    private ConcurrentHashMap<String, WillMessage> willStore = new ConcurrentHashMap<>();

    // 身份验证
    private IAuthenticator authenticator;
    // 消息存储
    private IMessageStore messageStore;
    // Session存储
    private ISessionStore sessionStore;
    // 订阅存储
    private SubscribeStore subscribeStore;

    @Autowired
    private UserService userService;
    @Autowired
    private GroupService groupService;
    @Autowired
    private FriendshipService friendshipService;
    @Autowired
    private UserGroupService userGroupService;
    @Autowired
    private MessageService messageService;
    @Autowired
    private OfflineMessageService offlineMessageService;

    public ProtocolProcess() {
        MapDBPersistentStore storge = new MapDBPersistentStore();
        this.authenticator = new IdentityAuthenticator();
        this.messageStore = storge;
        // 初始化存储
        this.messageStore.initStore();
        this.sessionStore = storge;
        this.subscribeStore = new SubscribeStore();
    }

    // 将此类单例
    private static ProtocolProcess INSTANCE;

    public static ProtocolProcess getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ProtocolProcess();
        }
        return INSTANCE;
    }

    /**
     * 处理协议的CONNECT消息类型
     */
    public void processConnect(Channel client, ConnectMessage connectMessage) {
        logger.info("-------------------------------------------------");
        logger.info("处理Connect的数据");
        logger.error("[查看保留位是否为0]{}", connectMessage.getVariableHeader().isReservedIsZero());
        // 首先查看保留位是否为0，不为0则断开连接,协议P24
        if (!connectMessage.getVariableHeader().isReservedIsZero()) {
            client.close();
            return;
        }

        logger.error("[protocol name]{},[protocol version]{}",
                connectMessage.getVariableHeader().getProtocolName(),
                connectMessage.getVariableHeader().getProtocolVersionNumber());
        // 处理protocol name和protocol version, 如果返回码!=0，sessionPresent必为0，协议P24,P32
        if (!connectMessage.getVariableHeader().getProtocolName().equals("MQTT") ||
                connectMessage.getVariableHeader().getProtocolVersionNumber() != 4) {

            ConnAckMessage connAckMessage = (ConnAckMessage) MqttMesageFactory.newMessage(
                    FixedHeader.getConnAckFixedHeader(),
                    new ConnAckVariableHeader(ConnectionStatus.UNACCEPTABLE_PROTOCOL_VERSION, false), null);

            client.writeAndFlush(connAckMessage);
            client.close();// 版本或协议名不匹配，则断开该客户端连接
            return;
        }

        // 处理Connect包的保留位不为0的情况，协议P24
        if (!connectMessage.getVariableHeader().isReservedIsZero()) {
            client.close();
        }

        logger.error("[ClientId]{}", connectMessage.getPayload().getClientId());
        // 处理clientId为null或长度为0的情况，协议P29
        if (connectMessage.getPayload().getClientId() == null || connectMessage.getPayload().getClientId().length() == 0) {
            // clientId为null的时候，cleanSession只能为1，此时给client设置一个随机的不存在的mac地址为ID，否则，断开连接
            if (connectMessage.getVariableHeader().isCleanSession()) {
                boolean isExist = true;
                String macClientId = StringTool.generalMacString();
                while (isExist) {
                    ConnectionDescriptor connectionDescriptor = clients.get(macClientId);
                    if (connectionDescriptor == null) {
                        connectMessage.getPayload().setClientId(macClientId);
                        isExist = false;
                    } else {
                        macClientId = StringTool.generalMacString();
                    }
                }
            } else {
                // reject null clientID
                logger.info("客户端ID为空，cleanSession为0，根据协议，不接收此客户端");
                ConnAckMessage connAckMessage = (ConnAckMessage) MqttMesageFactory.newMessage(
                        FixedHeader.getConnAckFixedHeader(),
                        new ConnAckVariableHeader(ConnectionStatus.IDENTIFIER_REJECTED, false), null);
                client.writeAndFlush(connAckMessage);
                client.close();
                return;
            }
        }

        //// 检查clientId的格式符合与否
        //if (!StringTool.isMacString(connectMessage.getPayload().getClientId())) {
        //    logger.info("客户端ID为{" + connectMessage.getPayload().getClientId() + "}，拒绝此客户端");
        //    ConnAckMessage connAckMessage = (ConnAckMessage) MqttMesageFactory.newMessage(
        //            FixedHeader.getConnAckFixedHeader(),
        //            new ConnAckVariableHeader(ConnectionStatus.IDENTIFIER_REJECTED, false), null);
        //    client.writeAndFlush(connAckMessage);
        //    client.close();
        //    return;
        //}

        // 如果会话中已经存储了这个新连接的ID，就关闭之前的clientId
        // if an old client with the same ID already exists close its session.
        if (clients.containsKey(connectMessage.getPayload().getClientId())) {
            logger.error("客户端ID{" + connectMessage.getPayload().getClientId() + "}已存在，强制关闭老连接");
            Channel oldChannel = clients.get(connectMessage.getPayload().getClientId()).getClient();
            boolean cleanSession = NettyAttrManager.getAttrCleanSession(oldChannel);
            // clean the subscriptions if the old used a cleanSession = true
            if (cleanSession) {
                cleanSession(connectMessage.getPayload().getClientId());
            }
            oldChannel.close();
        }

        // 若至此没问题，则将新客户端连接加入client的维护列表中
        ConnectionDescriptor connectionDescriptor = new ConnectionDescriptor(connectMessage.getPayload().getClientId(),
                client, connectMessage.getVariableHeader().isCleanSession());
        this.clients.put(connectMessage.getPayload().getClientId(), connectionDescriptor);

        // 处理心跳包时间，把心跳包时长和一些其他属性都添加到会话中，方便以后使用
        int keepAlive = connectMessage.getVariableHeader().getKeepAlive();
        logger.debug("连接的心跳包时长是 {" + keepAlive + "} s");
        NettyAttrManager.setAttrClientId(client, connectMessage.getPayload().getClientId());
        NettyAttrManager.setAttrCleanSession(client, connectMessage.getVariableHeader().isCleanSession());
        // 协议P29规定，在超过1.5个keepAlive的时间以上没收到心跳包PingReq，就断开连接(但这里要注意把单位是s转为ms)
        NettyAttrManager.setAttrKeepAlive(client, keepAlive);
        // 添加心跳机制处理的Handler
        client.pipeline().addFirst("idleStateHandler", new IdleStateHandler(keepAlive, Integer.MAX_VALUE, Integer.MAX_VALUE, TimeUnit.SECONDS));

        logger.error("[HasWill]{}", connectMessage.getVariableHeader().isHasWill());
        // 处理Will flag（遗嘱信息）,协议P26
        if (connectMessage.getVariableHeader().isHasWill()) {
            QoS willQos = connectMessage.getVariableHeader().getWillQoS();
            // 获取遗嘱信息的具体内容
            ByteBuf willPayload = Unpooled.buffer().writeBytes(connectMessage.getPayload().getWillMessage().getBytes());
            WillMessage willMessage = new WillMessage(connectMessage.getPayload().getWillTopic(),
                    willPayload, connectMessage.getVariableHeader().isWillRetain(), willQos);
            // 把遗嘱信息与和其对应的的clientId存储在一起
            willStore.put(connectMessage.getPayload().getClientId(), willMessage);
            logger.error("[WillMessage]{}", willMessage.toString());
        }

        logger.error("[Username]{}, [Password]{}", connectMessage.getPayload().getUsername(), connectMessage.getPayload().getPassword());
        // 处理身份验证（userNameFlag和passwordFlag）
        String username;
        String password;
        Integer userId = null;
        if (connectMessage.getVariableHeader().isHasUsername() && connectMessage.getVariableHeader().isHasPassword()) {
            username = connectMessage.getPayload().getUsername();
            password = connectMessage.getPayload().getPassword();
            //userId = userService.getUserByUserName(username).getUserId();
            // 此处对用户名和密码做验证
            if (!authenticator.checkValid(username, password)) {
                ConnAckMessage connAckMessage = (ConnAckMessage) MqttMesageFactory.newMessage(
                        FixedHeader.getConnAckFixedHeader(),
                        new ConnAckVariableHeader(ConnectionStatus.BAD_USERNAME_OR_PASSWORD, false), null);
                client.writeAndFlush(connAckMessage);
                return;
            }
        }

        // 处理cleanSession为1的情况
        if (connectMessage.getVariableHeader().isCleanSession()) {
            // 移除所有之前的session并开启一个新的，并且原先保存的subscribe之类的都得从服务器删掉
            cleanSession(connectMessage.getPayload().getClientId());
        }

        // TODO 此处生成一个token(以后每次客户端每次请求服务器，都必须先验证此token正确与否)，并把token保存到本地以及传回给客户端
        // 鉴权获取不应该在这里做

//        String token = StringTool.generalRandomString(32);
//        sessionStore.addSession(connectMessage.getClientId(), token);
//        //把荷载封装成json字符串
//        JSONObject jsonObject = new JSONObject();
//        try {
//			jsonObject.put("token", token);
//		} catch (JSONException e) {
//			e.printStackTrace();
//		}

        // TODO 连接成功，主动 publish 下发初始化信息，例如用户好友列表，群组等基础信息
        // 上述逻辑可以换 HTTP 协议实现，减少 IM 服务器逻辑复杂性和压力
        // 处理回写的CONNACK,并回写，协议P29
        ConnAckMessage okResp = null;
        // 协议32,session present的处理
        if (!connectMessage.getVariableHeader().isCleanSession() && sessionStore.searchSubscriptions(connectMessage.getPayload().getClientId())) {
            /**
             * 业务逻辑：初始化用户好友列表/订阅关系，群组等基础信息
             */
            //Map<String, Object> payload = new HashMap<>();
            //List<Friendship> friendshipList = friendshipService.selectByUserId(userId);
            //List<model.Message> messageList = messageService.selectMessageByFromId(userId);
            //payload.put("friendship", friendshipList);
            //payload.put("message", messageList);
            okResp = (ConnAckMessage) MqttMesageFactory.newMessage(
                    FixedHeader.getConnAckFixedHeader(), new ConnAckVariableHeader(ConnectionStatus.ACCEPTED, true), "登陆成功");
        } else {
            okResp = (ConnAckMessage) MqttMesageFactory.newMessage(
                    FixedHeader.getConnAckFixedHeader(), new ConnAckVariableHeader(ConnectionStatus.ACCEPTED, false), null);
        }


        client.writeAndFlush(okResp);
        logger.info("CONNACK处理完毕并成功发送");
        logger.info("连接的客户端clientId=" + connectMessage.getPayload().getClientId() + ", " + "cleanSession为" + connectMessage.getVariableHeader().isCleanSession());

        // 如果cleanSession=0,需要在重连的时候重发同一clientId存储在服务端的离线信息
        if (!connectMessage.getVariableHeader().isCleanSession()) {
            // force the republish of stored QoS1 and QoS2
            republishMessage(connectMessage.getPayload().getClientId());
        }
    }

    /**
     * 处理协议的publish消息类型，该方法先把public需要的事件提取出来
     */
    public void processPublish(Channel client, PublishMessage publishMessage) {
        logger.info("处理publish的数据：" + publishMessage.toString());
        String clientId = NettyAttrManager.getAttrClientId(client);
        final String topic = publishMessage.getVariableHeader().getTopic();
        final QoS qos = publishMessage.getFixedHeader().getQos();
        final ByteBuf message = publishMessage.getPayload();
        final int packetId = publishMessage.getVariableHeader().getPacketId();
        final boolean retain = publishMessage.getFixedHeader().isRetain();

        processPublish(clientId, topic, qos, retain, message, packetId);
    }

    /**
     * 处理遗言消息的发送
     */
    public void processPublish(Channel client, WillMessage willMessage) {
        logger.info("处理遗言的publish数据");
        String clientId = NettyAttrManager.getAttrClientId(client);
        final String topic = willMessage.getWillTopic();
        final ByteBuf message = willMessage.getWillMessage();
        final QoS qos = willMessage.getWillQoS();
        final boolean retain = willMessage.isWillRetain();

        processPublish(clientId, topic, qos, retain, message, null);
    }

    /**
     * 根据协议进行具体的处理，处理不同的Qos等级下的public事件
     *
     * @param recPacketId 此包ID只是客户端传过来的，用于发回pubAck用，发送给其他客户端的包ID，需要重新生成
     */
    private void processPublish(String clientId, String topic, QoS qos, boolean recRetain, ByteBuf message, Integer recPacketId) {
        logger.info("接收public消息:{clientId=" + clientId + ",Qos=" + qos + ",topic=" + topic + ",packetId=" + recPacketId + "}");
        //String publishKey = null;
        //int sendPacketId = PacketIdManager.getNextMessageId();

        // 1. 根据协议P34，Qos=3的时候，就关闭连接
        if (qos == QoS.RESERVED) {
            clients.get(clientId).getClient().close();
        }

        // 2. 根据协议P52，qos=0, Dup=0, 则把消息发送给所有注册的客户端即可
        if (qos == QoS.AT_MOST_ONCE) {
            boolean dup = false;
            boolean retain = false;

            sendPublishMessage(topic, qos, message, retain, dup);
        }

        // 3. 根据协议P53，publish的接受者需要发送该publish(Qos=1,Dup=0)消息给其他客户端，然后发送pubAck给该客户端。
        // 发送该publish消息时候，按此流程：存储消息→发送给所有人→等待pubAck到来→删除消息
        if (qos == QoS.AT_LEAST_ONCE) {
            boolean dup = false;
            boolean retain = false;

            sendPublishMessage(topic, qos, message, retain, dup);
            // 先发送 pubAck 给该客户端
            sendPubAck(clientId, recPacketId);
        }

        // 4. 根据协议P54，P55
        // 接收端：publish接收消息→存储包ID→发给其他客户端→发回pubRec→收到pubRel→抛弃第二步存储的包ID→发回pubcomp
        // 发送端：存储消息→发送publish(Qos=2,Dup=0)→收到pubRec→抛弃第一步存储的消息→存储pubRec的包ID→发送pubRel→收到pubcomp→抛弃pubRec包ID的存储
        if (qos == QoS.EXACTLY_ONCE) {
            boolean dup = false;
            boolean retain = false;
            // 存储pubRec的包ID
            messageStore.storePublicPacketId(clientId, recPacketId);
            sendPublishMessage(topic, qos, message, retain, dup);
            sendPubRec(clientId, recPacketId);
        }

        // 处理消息是否保留，注：publish报文中的主题名不能包含通配符(协议P35)，所以retain中保存的主题名不会有通配符
        if (recRetain) {
            if (qos == QoS.AT_MOST_ONCE) {
                messageStore.cleanRetained(topic);
            } else {
                messageStore.storeRetained(topic, message, qos);
            }
        }
    }

    /**
     * 处理协议的pubAck消息类型
     */
    public void processPubAck(Channel client, PacketIdVariableHeader pubAckVariableMessage) {
        String clientId = NettyAttrManager.getAttrClientId(client);
        int packetId = pubAckVariableMessage.getPacketId();
        String publishKey = String.format("%s%d", clientId, packetId);
        // 取消Publish重传任务
        QuartzManager.removeJob(publishKey, "publish", publishKey, "publish");
        // 删除临时存储用于重发的Publish消息
        messageStore.removeQosPublishMessage(publishKey);
        // 最后把使用完的包ID释放掉
        PacketIdManager.releaseMessageId(packetId);
    }

    /**
     * 处理协议的pubRec消息类型
     */
    public void processPubRec(Channel client, PacketIdVariableHeader pubRecVariableMessage) {
        String clientId = NettyAttrManager.getAttrClientId(client);
        int packetId = pubRecVariableMessage.getPacketId();
        String publishKey = String.format("%s%d", clientId, packetId);

        // 取消Publish重传任务,同时删除对应的值
        QuartzManager.removeJob(publishKey, "publish", publishKey, "publish");
        messageStore.removeQosPublishMessage(publishKey);
        // 此处须额外处理，根据不同的事件，处理不同的包ID
        messageStore.storePubRecPacketId(clientId, packetId);
        // 组装PubRel事件后，存储PubRel事件，并发回PubRel
        PubRelEvent pubRelEvent = new PubRelEvent(clientId, packetId);
        // 此处的Key和Publish的key一致
        messageStore.storePubRelMessage(publishKey, pubRelEvent);
        // 发回PubRel
        sendPubRel(clientId, packetId);
        // 开启PubRel重传事件
        Map<String, Object> jobParam = new HashMap<>();
        jobParam.put("ProtocolProcess", this);
        jobParam.put("pubRelKey", publishKey);
        QuartzManager.addJob(publishKey, "pubRel", publishKey, "pubRel", RePubRelJob.class, 10, 2, jobParam);
    }

    /**
     * 处理客户端过来的发布释放
     * 处理协议的pubRel消息类型
     */
    public void processPubRel(Channel client, PacketIdVariableHeader pubRelVariableMessage) {
        String clientId = NettyAttrManager.getAttrClientId(client);
        // 删除的是接收端的包ID
        int packetId = pubRelVariableMessage.getPacketId();
        messageStore.removePublicPacketId(clientId);
        sendPubComp(clientId, packetId);
    }

    /**
     * 处理协议的pubComp消息类型
     */
    public void processPubComp(Channel client, PacketIdVariableHeader pubcompVariableMessage) {
        String clientId = NettyAttrManager.getAttrClientId(client);
        int packetId = pubcompVariableMessage.getPacketId();
        String pubRelkey = String.format("%s%d", clientId, packetId);

        // 删除存储的PubRec包ID
        messageStore.removePubRecPacketId(clientId);
        // 取消PubRel的重传任务，删除临时存储的PubRel事件
        QuartzManager.removeJob(pubRelkey, "pubRel", pubRelkey, "pubRel");
        messageStore.removePubRelMessage(pubRelkey);
        // 最后把使用完的包ID释放掉
        PacketIdManager.releaseMessageId(packetId);
    }

    /**
     * 处理协议的subscribe消息类型
     */
    public void processSubscribe(Channel client, SubscribeMessage subscribeMessage) {
        String clientId = NettyAttrManager.getAttrClientId(client);
        boolean cleanSession = NettyAttrManager.getAttrCleanSession(client);
        logger.info("处理subscribe数据包，客户端ID={" + clientId + "},cleanSession={" + cleanSession + "}");

        // 一条subscribeMessage信息可能包含多个Topic和Qos
        List<TopicSubscribe> topicSubscribes = subscribeMessage.getPayload().getTopicSubscribes();
        List<Integer> grantedQosLevel = new ArrayList<>();
        // 依次处理订阅
        for (TopicSubscribe topicSubscribe : topicSubscribes) {
            String topicFilter = topicSubscribe.getTopicFilter();
            QoS qos = topicSubscribe.getRequestedQoS();
            Subscription newSubscription = new Subscription(clientId, topicFilter, qos, cleanSession);
            // 订阅新的订阅
            subscribeSingleTopic(newSubscription, topicFilter);
            // 生成suback荷载
            grantedQosLevel.add(qos.value());

            // TODO 存储订阅关系
        }

        // ack the client
        SubAckMessage subAckMessage = (SubAckMessage) MqttMesageFactory.newMessage(
                FixedHeader.getSubAckFixedHeader(),
                new PacketIdVariableHeader(subscribeMessage.getVariableHeader().getPacketId()),
                new SubAckPayload(grantedQosLevel));

        logger.info("回写subAck消息给订阅者，包ID={" + subscribeMessage.getVariableHeader().getPacketId() + "}");
        client.writeAndFlush(subAckMessage);
    }

    /**
     * 处理协议的unsubscribe消息类型
     */
    public void processUnsubscribe(Channel client, UnsubscribeMessage unsubscribeMessage) {
        String clientId = NettyAttrManager.getAttrClientId(client);
        int packetId = unsubscribeMessage.getVariableHeader().getPacketId();
        logger.info("处理unsubscribe数据包，客户端ID={" + clientId + "}");

        List<String> topicFilters = unsubscribeMessage.getPayload().getTopics();
        for (String topicFilter : topicFilters) {
            // 取消订阅树里的订阅
            subscribeStore.removeSubscription(topicFilter, clientId);
            sessionStore.removeSubscription(topicFilter, clientId);
        }

        // ack the client
        Message unSubAckMessage = MqttMesageFactory.newMessage(FixedHeader.getUnsubAckFixedHeader(), new PacketIdVariableHeader(packetId), null);
        logger.info("回写unSubAck信息给客户端，包ID为{" + packetId + "}");
        client.writeAndFlush(unSubAckMessage);
    }

    /**
     * 处理协议的pingReq消息类型
     */
    public void processPingReq(Channel client, Message pingReqMessage) {
        logger.info("收到心跳包" + pingReqMessage.toString());
        Message pingRespMessage = MqttMesageFactory.newMessage(FixedHeader.getPingRespFixedHeader(), null, null);
        // 重置心跳包计时器
        client.writeAndFlush(pingRespMessage);
    }

    /**
     * 处理协议的disconnect消息类型
     */
    public void processDisconnet(Channel client, Message disconnectMessage) {
        logger.info("连接断开消息：" + disconnectMessage.toString());

        String clientId = NettyAttrManager.getAttrClientId(client);
        boolean cleanSession = NettyAttrManager.getAttrCleanSession(client);
        logger.info("DISCONNECT client <{}> with clean session {}", clientId, cleanSession);

        if (cleanSession) {
            // cleanup the will store
            cleanSession(clientId);
        }
        willStore.remove(clientId);

        this.clients.remove(clientId);
        client.close();

        logger.info("DISCONNECT client <{}> finished", clientId, cleanSession);
    }

    /**
     * 清除会话，除了要从订阅树中删掉会话信息，还要从会话存储中删除会话信息
     */
    private void cleanSession(String clientId) {
        // 从订阅树中删掉会话信息
        subscribeStore.removeForClient(clientId);
        // 从会话存储中删除信息
        sessionStore.wipeSubscriptions(clientId);
    }

    /**
     * 在客户端重连以后，针对QoS1和Qos2的消息，重发存储的离线消息
     */
    private void republishMessage(String clientId) {
        // 取出需要重发的消息列表
        // 查看消息列表是否为空，为空则返回
        // 不为空则依次发送消息并从会话中删除此消息
        List<PublishEvent> publishedEvents = messageStore.listMessagesInSession(clientId);
        if (publishedEvents.isEmpty()) {
            logger.info("没有客户端{" + clientId + "}存储的离线消息");
            return;
        }

        logger.info("重发客户端{" + clientId + "}存储的离线消息");
        for (PublishEvent pubEvent : publishedEvents) {
            boolean dup = true;
            sendPublishMessage(pubEvent.getClientId(),
                    pubEvent.getTopic(),
                    pubEvent.getQos(),
                    Unpooled.buffer().writeBytes(pubEvent.getMessage()),
                    pubEvent.isRetain(),
                    pubEvent.getPacketId(),
                    dup);
            messageStore.removeMessageInSessionForPublish(clientId, pubEvent.getPacketId());
        }
    }

    /**
     * 在未收到对应包的情况下，重传Publish消息
     */
    public void reUnKnowPublishMessage(String publishKey) {
        // 根据publishKey搜索之前保存的publishMessage
        PublishEvent pubEvent = messageStore.searchQosPublishMessage(publishKey);
        if (pubEvent != null) {
            logger.info("重发PublishKey为{" + publishKey + "}的Publish离线消息");
            boolean dup = true;
            PublishMessage publishMessage = (PublishMessage) MqttMesageFactory.newMessage(
                    FixedHeader.getPublishFixedHeader(dup, pubEvent.getQos(), pubEvent.isRetain()),
                    new PublishVariableHeader(pubEvent.getTopic(), pubEvent.getPacketId()),
                    Unpooled.buffer().writeBytes(pubEvent.getMessage()));
            // 从会话列表中取出会话，然后通过此会话发送publish消息
            this.clients.get(pubEvent.getClientId()).getClient().writeAndFlush(publishMessage);
        }
    }

    /**
     * 在未收到对应包的情况下，重传PubRel消息
     */
    public void reUnKnowPubRelMessage(String pubRelKey) {
        PubRelEvent pubEvent = messageStore.searchPubRelMessage(pubRelKey);
        if (pubEvent != null) {
            logger.info("重发PubRelKey为{" + pubRelKey + "}的PubRel离线消息");
            sendPubRel(pubEvent.getClientId(), pubEvent.getPacketId());
            //messageStore.removeQosPublishMessage(pubRelKey);
        }
    }

    /**
     * 取出所有匹配topic的客户端，然后发送public消息给客户端
     */
    private void sendPublishMessage(String topic, QoS originQos, ByteBuf message, boolean retain, boolean dup) {
        for (final Subscription sub : subscribeStore.getClientListFromTopic(topic)) {
            logger.error("[取出所有匹配topic的客户端]{}", sub.toString());
            String clientId = sub.getClientId();
            Integer sendPacketId = PacketIdManager.getNextMessageId();
            String publishKey = String.format("%s%d", clientId, sendPacketId);
            QoS qos = originQos;

            // 协议P43提到，假设请求的QoS级别被授权，客户端接收的PUBLISH消息的QoS级别小于或等于这个级别，PUBLISH 消息的级别取决于发布者的原始消息的QoS级别
            if (originQos.ordinal() > sub.getRequestedQos().ordinal()) {
                qos = sub.getRequestedQos();
            }

            PublishMessage publishMessage = (PublishMessage) MqttMesageFactory.newMessage(
                    FixedHeader.getPublishFixedHeader(dup, qos, retain), new PublishVariableHeader(topic, sendPacketId), message);

            if (this.clients == null) {
                throw new RuntimeException("内部错误，clients为null");
            } else {
                logger.debug("clients为{" + this.clients + "}");
            }

            if (this.clients.get(clientId) == null) {
                throw new RuntimeException("不能从会话列表{" + this.clients + "}中找到clientId:{" + clientId + "}");
            } else {
                logger.debug("从会话列表{" + this.clients + "}查找到clientId:{" + clientId + "}----------------------------------取出所有匹配topic的客户端，然后发送public消息给客户端");
            }

            if (originQos == QoS.AT_MOST_ONCE) {
                publishMessage = (PublishMessage) MqttMesageFactory.newMessage(
                        FixedHeader.getPublishFixedHeader(dup, qos, retain), new PublishVariableHeader(topic), message);
                // 从会话列表中取出会话，然后通过此会话发送publish消息
                this.clients.get(clientId).getClient().writeAndFlush(publishMessage);
            } else {
                publishKey = String.format("%s%d", clientId, sendPacketId);// 针对每个重新生成key，保证消息ID不会重复
                // 将ByteBuf转变为byte[]
                byte[] messageBytes = new byte[message.readableBytes()];
                message.getBytes(message.readerIndex(), messageBytes);
                PublishEvent storePublishEvent = new PublishEvent(topic, qos, messageBytes, retain, clientId, sendPacketId);

                // 从会话列表中取出会话，然后通过此会话发送publish消息
                this.clients.get(clientId).getClient().writeAndFlush(publishMessage);
                // 存临时Publish消息，用于重发
                messageStore.storeQosPublishMessage(publishKey, storePublishEvent);
                // 开启Publish重传任务，在指定时间内未收到PubAck包则重传该条Publish信息
                Map<String, Object> jobParam = new HashMap<>();
                jobParam.put("ProtocolProcess", this);
                jobParam.put("publishKey", publishKey);
                QuartzManager.addJob(publishKey, "publish", publishKey, "publish", RePublishJob.class, 10, 2, jobParam);
            }

            logger.info("服务器发送消息给客户端{" + clientId + "},topic{" + topic + "},qos{" + qos + "}");

            // 如果CleanSession==false，则存储publish的离线消息事件PublishEvent，为CleanSession=0的情况做重发准备
            if (!sub.isCleanSession()) {
                // 将ByteBuf转变为byte[]
                byte[] messageBytes = new byte[message.readableBytes()];
                message.getBytes(message.readerIndex(), messageBytes);
                PublishEvent newPublishEvt = new PublishEvent(topic, qos, messageBytes, retain, sub.getClientId(), sendPacketId != null ? sendPacketId : 0);
                messageStore.storeMessageToSessionForPublish(newPublishEvt);
            }
        }
    }

    /**
     * 发送publish消息给指定ID的客户端
     */
    private void sendPublishMessage(String clientId, String topic, QoS qos, ByteBuf message, boolean retain, Integer packetId, boolean dup) {
        logger.info("发送pulicMessage给指定客户端");

        String publishKey = String.format("%s%d", clientId, packetId);

        PublishMessage publishMessage = (PublishMessage) MqttMesageFactory.newMessage(
                FixedHeader.getPublishFixedHeader(dup, qos, retain),
                new PublishVariableHeader(topic, packetId),
                message);

        if (this.clients == null) {
            throw new RuntimeException("内部错误，clients为null");
        } else {
            logger.debug("clients为{" + this.clients + "}");
        }

        if (this.clients.get(clientId) == null) {
            throw new RuntimeException("不能从会话列表{" + this.clients + "}中找到clientId:{" + clientId + "}");
        } else {
            logger.debug("从会话列表{" + this.clients + "}查找到clientId:{" + clientId + "}--------------------------发送publish消息给指定ID的客户端");
        }

        if (qos == QoS.AT_MOST_ONCE) {
            publishMessage = (PublishMessage) MqttMesageFactory.newMessage(
                    FixedHeader.getPublishFixedHeader(dup, qos, retain),
                    new PublishVariableHeader(topic),
                    message);
            //从会话列表中取出会话，然后通过此会话发送publish消息
            this.clients.get(clientId).getClient().writeAndFlush(publishMessage);
        } else {
            publishKey = String.format("%s%d", clientId, packetId);//针对每个重生成key，保证消息ID不会重复
            //将ByteBuf转变为byte[]
            byte[] messageBytes = new byte[message.readableBytes()];
            message.getBytes(message.readerIndex(), messageBytes);
            PublishEvent storePublishEvent = new PublishEvent(topic, qos, messageBytes, retain, clientId, packetId);

            //从会话列表中取出会话，然后通过此会话发送publish消息
            this.clients.get(clientId).getClient().writeAndFlush(publishMessage);
            //存临时Publish消息，用于重发
            messageStore.storeQosPublishMessage(publishKey, storePublishEvent);
            //开启Publish重传任务，在制定时间内未收到PubAck包则重传该条Publish信息
            Map<String, Object> jobParam = new HashMap<String, Object>();
            jobParam.put("ProtocolProcess", this);
            jobParam.put("publishKey", publishKey);
            QuartzManager.addJob(publishKey, "publish", publishKey, "publish", RePublishJob.class, 10, 2, jobParam);
        }
    }

    /**
     * 发送保存的Retain消息
     */
    private void sendPublishMessage(String clientId, String topic, QoS qos, ByteBuf message, boolean retain) {
        int packetId = PacketIdManager.getNextMessageId();
        sendPublishMessage(clientId, topic, qos, message, retain, packetId, false);
    }

    /**
     * 回写PubAck消息给发来publish的客户端
     */
    private void sendPubAck(String clientId, Integer packetId) {
        logger.info("发送PubAck消息给客户端");

        Message pubAckMessage = MqttMesageFactory.newMessage(
                FixedHeader.getPubAckFixedHeader(), new PacketIdVariableHeader(packetId), null);

        try {
            if (this.clients == null) {
                throw new RuntimeException("内部错误，clients为null");
            } else {
                logger.debug("clients为{" + this.clients + "}");
            }

            if (this.clients.get(clientId) == null) {
                throw new RuntimeException("不能从会话列表{" + this.clients + "}中找到clientId:{" + clientId + "}");
            } else {
                logger.debug("从会话列表{" + this.clients + "}查找到clientId:{" + clientId + "}---------------回写PubAck消息给发来publish的客户端");
            }

            this.clients.get(clientId).getClient().writeAndFlush(pubAckMessage);
        } catch (Throwable t) {
            logger.error(null, t);
        }
    }

    /**
     * 回写PubRec消息给发来publish的客户端
     */
    private void sendPubRec(String clientId, Integer packetId) {
        logger.trace("发送PubRec消息给客户端");

        Message pubRecMessage = MqttMesageFactory.newMessage(
                FixedHeader.getPubRecFixedHeader(), new PacketIdVariableHeader(packetId), null);

        try {
            if (this.clients == null) {
                throw new RuntimeException("内部错误，clients为null");
            } else {
                logger.debug("clients为{" + this.clients + "}");
            }

            if (this.clients.get(clientId) == null) {
                throw new RuntimeException("不能从会话列表{" + this.clients + "}中找到clientId:{" + clientId + "}");
            } else {
                logger.debug("从会话列表{" + this.clients + "}查找到clientId:{" + clientId + "}--------------------回写PubRec消息给发来publish的客户端");
            }

            this.clients.get(clientId).getClient().writeAndFlush(pubRecMessage);
        } catch (Throwable t) {
            logger.error(null, t);
        }
    }

    /**
     * 回写PubRel消息给发来publish的客户端
     */
    private void sendPubRel(String clientId, Integer packetId) {
        logger.trace("发送PubRel消息给客户端");

        Message pubRelMessage = MqttMesageFactory.newMessage(
                FixedHeader.getPubRelFixedHeader(), new PacketIdVariableHeader(packetId), null);

        try {
            if (this.clients == null) {
                throw new RuntimeException("内部错误，client为null");
            } else {
                logger.debug("client为{" + this.clients + "}");
            }

            if (this.clients.get(clientId) == null) {
                throw new RuntimeException("不能从会话列表{" + this.clients + "}中找到clientId:{" + clientId + "}");
            } else {
                logger.debug("从会话列表{" + this.clients + "}查找到clientId:{" + clientId + "}---------------------回写PubRel消息给发来publish的客户端");
            }

            this.clients.get(clientId).getClient().writeAndFlush(pubRelMessage);
        } catch (Throwable t) {
            logger.error(null, t);
        }
    }

    /**
     * 回写PubComp消息给发来publish的客户端
     */
    private void sendPubComp(String clientId, Integer packetId) {
        logger.trace("发送PubComp消息给客户端");

        Message pubCompMessage = MqttMesageFactory.newMessage(
                FixedHeader.getPubCompFixedHeader(), new PacketIdVariableHeader(packetId), null);

        try {
            if (this.clients == null) {
                throw new RuntimeException("内部错误，clients为null");
            } else {
                logger.debug("clients为{" + this.clients + "}");
            }

            if (this.clients.get(clientId) == null) {
                throw new RuntimeException("不能从会话列表{" + this.clients + "}中找到clientId:{" + clientId + "}");
            } else {
                logger.debug("从会话列表{" + this.clients + "}查找到clientId:{" + clientId + "}-------------------回写PubComp消息给发来publish的客户端");
            }

            this.clients.get(clientId).getClient().writeAndFlush(pubCompMessage);
        } catch (Throwable t) {
            logger.error(null, t);
        }
    }

    /**
     * 处理一个单一订阅，存储到会话和订阅树
     */
    private void subscribeSingleTopic(Subscription newSubscription, final String topic) {
        logger.info("订阅topic{" + topic + "},Qos为{" + newSubscription.getRequestedQos() + "}");
        String clientId = newSubscription.getClientId();
        sessionStore.addNewSubscription(newSubscription, clientId);
        subscribeStore.addSubscrpition(newSubscription);
        // TODO 此处还需要将此订阅之前存储的信息发出去
        Collection<IMessageStore.StoredMessage> messages = messageStore.searchRetained(topic);
        for (IMessageStore.StoredMessage storedMsg : messages) {
            logger.debug("send publish message for topic {" + topic + "}");
            sendPublishMessage(newSubscription.getClientId(), storedMsg.getTopic(), storedMsg.getQos(), Unpooled.buffer().writeBytes(storedMsg.getPayload()), true);
        }
    }
}
