/* SPDX-License-Identifier: GPL-2.0-only
 * Standalone runtime bootstrap, adapted from DFRoot bootstrap environment setup.
 */
#define _GNU_SOURCE
#include "diagnostic_android.h"
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <sched.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

#define APP "/data/user_de/0/lp.pods"
#define CONFIG APP "/files/root-channel.config"

static void marker(const char *path) {int fd=open(path,O_CREAT|O_WRONLY|O_CLOEXEC,0600);if(fd>=0)close(fd);}
static int environment(void) {
    FILE *process=popen("/system/bin/pidof zygote64 zygote","r");if(!process)return -1;
    int pid=0;(void)fscanf(process,"%d",&pid);pclose(process);if(pid<=0)return -1;
    /* Kernel helpers may inherit init's bootstrap APEX namespace. ART needs
     * the normal zygote namespace; keep the change inside this broker child. */
    char namespace_path[64];snprintf(namespace_path,sizeof(namespace_path),"/proc/%d/ns/mnt",pid);
    int namespace_fd=open(namespace_path,O_RDONLY|O_CLOEXEC);if(namespace_fd<0)return -1;
    struct stat current_ns,target_ns;
    if(stat("/proc/self/ns/mnt",&current_ns)||fstat(namespace_fd,&target_ns)){close(namespace_fd);return -1;}
    if(current_ns.st_ino!=target_ns.st_ino && setns(namespace_fd,CLONE_NEWNS)){close(namespace_fd);return -1;}
    close(namespace_fd);
    char path[64];snprintf(path,sizeof(path),"/proc/%d/environ",pid);
    int fd=open(path,O_RDONLY|O_CLOEXEC);if(fd<0)return -1;
    static char values[65536];ssize_t length=read(fd,values,sizeof(values)-1);close(fd);
    if(length<=0)return -1;values[length]=0;
    for(char *at=values,*end=values+length;at<end;at+=strlen(at)+1)if(strchr(at,'='))putenv(at);
    return 0;
}
static int read_apk(char *apk,size_t size) {
    int fd=open(CONFIG,O_RDONLY|O_CLOEXEC|O_NOFOLLOW);if(fd<0)return -1;
    struct stat st;if(fstat(fd,&st)||!S_ISREG(st.st_mode)||st.st_uid<10000||(st.st_mode&0077)||st.st_size>2048){close(fd);return -1;}
    FILE *file=fdopen(fd,"r");if(!file){close(fd);return -1;}
    char socket[128],nonce[128];
    int ok=fgets(socket,sizeof(socket),file)&&fgets(nonce,sizeof(nonce),file)&&fgets(apk,(int)size,file);
    fclose(file);if(!ok)return -1;
    apk[strcspn(apk,"\r\n")]=0;
    size_t length=strlen(apk);
    return !strncmp(apk,"/data/app/",10)&&length>4&&!strcmp(apk+length-4,".apk")?0:-1;
}
static int broker(int wait_domain) {
    if(getuid()!=0)return 77;
    if(wait_domain) {
        int ready=0;
        for(int i=0;i<150;i++) {
            int fd=open("/proc/self/attr/current",O_WRONLY|O_CLOEXEC);
            if(fd>=0){const char domain[]="u:r:ksu:s0";ready=write(fd,domain,sizeof(domain)-1)==sizeof(domain)-1;close(fd);}
            if(ready)break;usleep(200000);
        }
        if(!ready)return 78;
    }
    if(mkdir("/data/adb/handoff",0700)&&errno!=EEXIST)return 79;
    struct stat directory;
    if(lstat("/data/adb/handoff",&directory)||!S_ISDIR(directory.st_mode)||directory.st_uid!=0||(directory.st_mode&0022))return 79;
    int lock=open("/data/adb/handoff/.linkpods-broker.lock",O_CREAT|O_RDWR|O_NOFOLLOW,0600);
    if(lock<0)return 79;
    if(flock(lock,LOCK_EX|LOCK_NB)){close(lock);return errno==EWOULDBLOCK?0:79;}
    pid_t child=fork();if(child<0){close(lock);return 80;}
    if(child==0) {
        char apk[1024];if(read_apk(apk,sizeof(apk))||environment())_exit(81);
        setenv("CLASSPATH",apk,1);prctl(PR_SET_NAME,"linkpods-root",0,0,0);
        execl("/system/bin/app_process","app_process","/system/bin","local.airpods.handoffprobe.RootBrokerMain",(char*)NULL);
        _exit(127);
    }
    int status;while(waitpid(child,&status,0)<0)if(errno!=EINTR){close(lock);return 82;}
    close(lock);return WIFEXITED(status)?WEXITSTATUS(status):83;
}
static void spawn(int wait_domain) {
    if(fork()!=0)return;
    setsid();int fd=open(APP "/files/root-bootstrap.log",O_WRONLY|O_CREAT|O_APPEND|O_NOFOLLOW,0600);
    if(fd>=0){dup2(fd,STDOUT_FILENO);dup2(fd,STDERR_FILENO);if(fd>2)close(fd);}
    int null=open("/dev/null",O_RDONLY);if(null>=0){dup2(null,STDIN_FILENO);if(null>2)close(null);}
    int result=broker(wait_domain);dprintf(STDERR_FILENO,"Root broker exited: %d\n",result);_exit(result);
}
int main(int argc,char **argv) {
    if(getuid()!=0)return 77;
    if(argc==2&&!strcmp(argv[1],"--broker")){spawn(0);return 0;}
    if(argc!=1)return 64;
    marker("/dev/dfm1");
    int result=dfroot_diagnostic_preflight(APP "/ksud",APP "/diagnostic.ko");
    if(result){marker("/dev/dfme2");return result;}
    spawn(1);
    result=dfroot_diagnostic_run(APP "/ksud",APP "/diagnostic.ko","lp.pods");
    marker(result?"/dev/dfme2":"/dev/dfm6");return result?1:0;
}
