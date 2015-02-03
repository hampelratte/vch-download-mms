package de.berlios.vch.download.mms;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;

import org.apache.mina.core.future.IoFuture;
import org.apache.mina.core.future.IoFutureListener;
import org.apache.mina.core.service.IoHandler;
import org.apache.mina.core.session.IdleStatus;
import org.apache.mina.core.session.IoSession;
import org.hampelratte.net.mms.asf.io.ASFInputStream;
import org.hampelratte.net.mms.asf.objects.ASFFilePropertiesObject;
import org.hampelratte.net.mms.asf.objects.ASFToplevelHeader;
import org.hampelratte.net.mms.client.Client;
import org.hampelratte.net.mms.client.listeners.MMSMessageListener;
import org.hampelratte.net.mms.client.listeners.MMSPacketListener;
import org.hampelratte.net.mms.data.MMSHeaderPacket;
import org.hampelratte.net.mms.data.MMSMediaPacket;
import org.hampelratte.net.mms.data.MMSPacket;
import org.hampelratte.net.mms.messages.MMSMessage;
import org.hampelratte.net.mms.messages.server.ReportEndOfStream;
import org.hampelratte.net.mms.messages.server.ReportStreamSwitch;
import org.osgi.service.log.LogService;

import de.berlios.vch.download.AbstractDownload;
import de.berlios.vch.parser.IVideoPage;

public class MmsDownload extends AbstractDownload implements MMSMessageListener, MMSPacketListener, IoHandler {

    private LogService logger;

    private String host;
    private int port = 1755;
    private String path;
    private String file;
    private File localFile;

    protected Client client;

    private MMSHeaderPacket hp;

    int progress;

    private long packetCount;

    private long packetReadCount;

    public MmsDownload(IVideoPage video, LogService logger) {
        super(video);
        this.logger = logger;

        // file
        URI uri = video.getVideoUri();
        String p = uri.getPath();
        file = p.substring(p.lastIndexOf('/'));
        if (uri.getQuery() != null) {
            file += "?" + uri.getQuery();
        }
    }

    @Override
    public int getProgress() {
        return progress;
    }

    private void extractConnectInfo() {
        URI uri = getVideoPage().getVideoUri();

        // host
        host = uri.getHost();

        // port
        port = uri.getPort();

        // directory path
        path = uri.getPath();
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        path = path.substring(0, path.lastIndexOf('/'));

        logger.log(LogService.LOG_DEBUG, "Host: " + host);
        logger.log(LogService.LOG_DEBUG, "Port: " + Integer.toString(port));
        logger.log(LogService.LOG_DEBUG, "Path: " + path);
        logger.log(LogService.LOG_DEBUG, "File: " + file);
    }

    @Override
    public boolean isPauseSupported() {
        return client != null && client.isPauseSupported();
    }

    @Override
    public float getSpeed() {
        if (getStatus() == Status.DOWNLOADING) {
            return (float) client.getSpeed();
        } else {
            return -1;
        }
    }

    @Override
    public boolean isRunning() {
        return getStatus() == Status.DOWNLOADING || getStatus() == Status.STARTING;
    }

    public void startDownload() {
        setStatus(Status.STARTING);
        setException(null);
        extractConnectInfo();

        // create new client
        connectExceptionCount = 0;
        client = new Client(getVideoPage().getVideoUri());

        // register message listeners
        client.addMessageListener(this);
        client.addPacketListener(this);
        client.addAdditionalIoHandler(this);

        // open the connection
        try {
            client.connect(null);
        } catch (Exception e1) {
            error("Couldn't connect to host", e1);
        }

        // initialize the outputstream, if necessary
        if (getOutputStream() == null) {
            if (getLocalFile() != null) {
                try {
                    localFile = new File(getLocalFile());
                    setOutputStream(new FileOutputStream(localFile));
                } catch (FileNotFoundException e) {
                    error("Couldn't open output file", e);
                }
            } else {
                logger.log(LogService.LOG_DEBUG, "Local file and output stream is null. Data will be discarded");
            }
        }

        // mina runs in an own thread, so this thread would end immediately
        // after starting the download. so we have to block this thread, so that
        // the DownloadManager doesn't start more than numberOfConcurrentDownloads
        while (isRunning()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    @Override
    public void stop() {
        setStatus(Status.STOPPED);
        if (client != null) {
            try {
                client.disconnect(new IoFutureListener<IoFuture>() {
                    @Override
                    public void operationComplete(IoFuture arg0) {
                        // do nothing
                    }
                });
            } catch (RuntimeException e) {
                // probably the client wasn't connected
                error(e.getLocalizedMessage(), e);
            }
        }
    }

    @Override
    public void cancel() {
        stop();
        setStatus(Status.CANCELED);

        // delete the video file
        if (localFile != null && localFile.exists()) {
            boolean deleted = localFile.delete();
            if (!deleted) {
                logger.log(LogService.LOG_WARNING, "Couldn't delete file " + localFile.getAbsolutePath());
            }
        }
    }

    @Override
    public void messageReceived(MMSMessage msg) {
        if (Thread.currentThread().isInterrupted()) {
            stop();
        }

        if (msg instanceof ReportStreamSwitch) {
            // great, we can start the streaming
            long startPacket = 0;

            // check if resuming is supported
            if (isPauseSupported()) {
                startPacket = packetReadCount;
            }

            // start the streaming
            client.startStreaming(startPacket);
        } else if (msg instanceof ReportEndOfStream) {
            setStatus(Status.FINISHED);
        }
    }

    @Override
    public void packetReceived(MMSPacket mmspacket) {
        if (Thread.currentThread().isInterrupted()) {
            stop();
        }

        if (mmspacket instanceof MMSHeaderPacket) {
            hp = (MMSHeaderPacket) mmspacket;
            try {
                ByteArrayInputStream bin = new ByteArrayInputStream(hp.getData());
                ASFInputStream asfin = new ASFInputStream(bin);
                ASFToplevelHeader asfHeader = (ASFToplevelHeader) asfin.readASFObject();
                logger.log(LogService.LOG_DEBUG, "ASF header: " + asfHeader);
                ASFFilePropertiesObject fileprops = (ASFFilePropertiesObject) asfHeader.getNestedHeader(ASFFilePropertiesObject.class);
                if (fileprops != null) {
                    packetCount = fileprops.getDataPacketCount();
                    logger.log(LogService.LOG_DEBUG, fileprops.toString());
                } else {
                    packetCount = -1;
                }
            } catch (Exception e) {
                logger.log(LogService.LOG_WARNING, "Ignoring unknown ASF header object", e);
            }

            // if the download supports pausing, we just have to
            // jump to the last read packet, when the streaming
            // continues. the header packet can be ignored
            // if the download doesn't support pausing, we have to start
            // from the beginning.
            if (isPauseSupported() && packetReadCount > 0) {
                // jump to last position
                // TODO at the moment, we jump to the end of the file.
                // would be better to jump to the end of the last read packet
                try {
                    if (getOutputStream() != null) {
                        getOutputStream().close();
                        setOutputStream(new FileOutputStream(getLocalFile(), true));
                    }
                } catch (IOException e) {
                    error("Couldn't jump to last position in file", e);
                }
            } else {
                writePacketOutputStream(hp);
            }
        } else if (mmspacket instanceof MMSMediaPacket) {
            packetReadCount++;
            progress = (int) ((double) packetReadCount / (double) packetCount * 100);
            setStatus(Status.DOWNLOADING);
            writePacketOutputStream(mmspacket);
        }
    }

    private void writePacketOutputStream(MMSPacket packet) {
        try {
            if (getOutputStream() != null) {
                getOutputStream().write(packet.getData());
            }
        } catch (IOException e) {
            error("Couldn't write MMS packet to file", e);
        }
    }

    @Override
    public void run() {
        startDownload();
    }

    @Override
    public synchronized String getLocalFile() {
        String filename = file.substring(1);

        // cut off query parameters
        if (filename.contains("?")) {
            filename = filename.substring(0, filename.indexOf('?'));
        }

        // replace anything other than a-z, A-Z or 0-9 with _
        String title = getVideoPage().getTitle().replaceAll("[^a-zA-z0-9]", "_");

        return getDestinationDir() + File.separator + title + "_" + filename;
    }

    private int connectExceptionCount = 0;

    @Override
    public void exceptionCaught(IoSession session, Throwable cause) throws Exception {
        // ignore the first connect exception, because the client will try another connection
        // with the mms over http protocol
        if (cause instanceof ConnectException) {
            if (++connectExceptionCount < 2) {
                return;
            }
        }

        stop();
        setStatus(Status.FAILED);
        setException(cause);
    }

    @Override
    public void messageReceived(IoSession arg0, Object arg1) throws Exception {
    }

    @Override
    public void messageSent(IoSession arg0, Object arg1) throws Exception {
    }

    @Override
    public void sessionClosed(IoSession session) throws Exception {
        if (getProgress() < 100 && getStatus() != Status.STOPPED) {
            setException(new RuntimeException("Client closed by Server"));
            setStatus(Status.FAILED);
            progress = -1;
        }
    }

    @Override
    public void sessionCreated(IoSession arg0) throws Exception {
    }

    @Override
    public void sessionIdle(IoSession arg0, IdleStatus arg1) throws Exception {
    }

    @Override
    public void sessionOpened(IoSession arg0) throws Exception {
    }
}