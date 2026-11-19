/*
 * PTY native untuk tab Terminal.
 *
 * Android tidak punya API Java untuk membuat pty, jadi shell interaktif di
 * dalam proot butuh modul native ini: buka pty pair, fork, lalu di anak
 * pindahkan pty slave ke stdin/stdout/stderr dan exec argv[0] (proot).
 *
 * Tidak memakai system()/sh -c: argv dan env datang sudah jadi dari Kotlin
 * (TerminalCommand.build), jadi tidak ada parsing shell di sini.
 */

#include <jni.h>

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#include <android/log.h>

#define LOG_TAG "TerminalPty"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/** Dipakai bila env dari Kotlin kosong. */
static char *const empty_env[] = {NULL};

/** Set ukuran window pty; gagal di sini tidak fatal. */
static void set_winsize(int fd, int rows, int cols) {
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;
    ioctl(fd, TIOCSWINSZ, &ws);
}

/** Salin jstring[] ke char*[] + NULL terminator. NULL bila kosong/gagal. */
static char **to_argv(JNIEnv *env, jobjectArray array) {
    if (array == NULL) return NULL;
    jsize count = (*env)->GetArrayLength(env, array);
    if (count == 0) return NULL;

    char **result = calloc((size_t) count + 1, sizeof(char *));
    if (result == NULL) return NULL;

    for (jsize i = 0; i < count; i++) {
        jstring item = (jstring) (*env)->GetObjectArrayElement(env, array, i);
        if (item == NULL) {
            result[i] = NULL;
            continue;
        }
        const char *chars = (*env)->GetStringUTFChars(env, item, NULL);
        result[i] = chars ? strdup(chars) : NULL;
        if (chars) (*env)->ReleaseStringUTFChars(env, item, chars);
        (*env)->DeleteLocalRef(env, item);
    }
    return result;
}

static void free_argv(char **argv) {
    if (argv == NULL) return;
    for (char **p = argv; *p != NULL; p++) free(*p);
    free(argv);
}

JNIEXPORT jboolean JNICALL
Java_com_arkacode_app_core_TerminalPty_nativeSpawn(
        JNIEnv *env, jclass clazz,
        jobjectArray jargv, jobjectArray jenv, jstring jcwd,
        jint rows, jint cols, jintArray jout) {
    (void) clazz;

    if (jargv == NULL || jout == NULL) return JNI_FALSE;
    jsize out_len = (*env)->GetArrayLength(env, jout);
    if (out_len < 2) return JNI_FALSE;

    const char *cwd = jcwd ? (*env)->GetStringUTFChars(env, jcwd, NULL) : NULL;
    if (jcwd != NULL && cwd == NULL) return JNI_FALSE;

    char **argv = to_argv(env, jargv);
    char **envp = to_argv(env, jenv);
    if (argv == NULL || argv[0] == NULL) {
        free_argv(argv);
        free_argv(envp);
        if (cwd) (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
        return JNI_FALSE;
    }

    const char *slave_name;
    int master = -1;
    int slave = -1;
    pid_t pid;

    // O_CLOEXEC: master tidak boleh bocor ke proot tab lain lewat fork/exec.
    master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0) {
        LOGE("posix_openpt gagal: %s", strerror(errno));
        goto fail;
    }
    if (grantpt(master) != 0 || unlockpt(master) != 0) {
        LOGE("grantpt/unlockpt gagal: %s", strerror(errno));
        goto fail;
    }
    slave_name = ptsname(master);
    if (slave_name == NULL) {
        LOGE("ptsname gagal: %s", strerror(errno));
        goto fail;
    }
    // O_CLOEXEC juga di slave: hanya fd 0/1/2 hasil dup2 yang ikut exec.
    slave = open(slave_name, O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (slave < 0) {
        LOGE("open slave gagal: %s", strerror(errno));
        goto fail;
    }

    pid = fork();
    if (pid < 0) {
        LOGE("fork gagal: %s", strerror(errno));
        goto fail;
    }

    if (pid == 0) {
        /* Anak: jadikan pty slave sebagai controlling terminal, lalu exec. */
        // Gagal setsid = bukan pemimpin sesi; induk nanti memakai kill(-pid),
        // jadi jangan lanjut exec (pid bisa menunjuk grup proses lain).
        if (setsid() < 0) _exit(127);
        ioctl(slave, TIOCSCTTY, 0);
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);
        close(master);
        // Mask sinyal warisan thread pemanggil bisa memblokir SIGCHLD/job
        // control di shell; execve sendiri tidak mereset mask.
        sigset_t empty_mask;
        sigemptyset(&empty_mask);
        sigprocmask(SIG_SETMASK, &empty_mask, NULL);
        if (cwd != NULL) {
            if (chdir(cwd) != 0) {
                /* cwd hilang bukan alasan batal: shell tetap bisa jalan di "/". */
                chdir("/");
            }
        }
        /* Lingkungan bersih: env dari Kotlin sudah lengkap. */
        execve(argv[0], argv, envp ? envp : empty_env);
        _exit(127);
    }

    /* Induk: pty dimiliki app, anak hanya memakai slave. */
    close(slave);
    slave = -1;
    set_winsize(master, rows, cols);

    jint out[2];
    out[0] = (jint) master;
    out[1] = (jint) pid;
    (*env)->SetIntArrayRegion(env, jout, 0, 2, out);

    free_argv(argv);
    free_argv(envp);
    if (cwd) (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
    return JNI_TRUE;

fail:
    if (slave >= 0) close(slave);
    if (master >= 0) close(master);
    free_argv(argv);
    free_argv(envp);
    if (cwd) (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_arkacode_app_core_TerminalPty_nativeResize(
        JNIEnv *env, jclass clazz, jint fd, jint rows, jint cols) {
    (void) env;
    (void) clazz;
    if (fd < 0) return;
    set_winsize(fd, rows, cols);
}

JNIEXPORT void JNICALL
Java_com_arkacode_app_core_TerminalPty_nativeSignal(
        JNIEnv *env, jclass clazz, jint pid, jint sig) {
    (void) env;
    (void) clazz;
    if (pid <= 0) return;
    // Anak adalah pemimpin sesi/grup (setsid), jadi -pid = seluruh grup:
    // shell + proot + turunannya ikut berhenti, bukan hanya proot.
    kill(-(pid_t) pid, sig);
}

JNIEXPORT jint JNICALL
Java_com_arkacode_app_core_TerminalPty_nativeWait(
        JNIEnv *env, jclass clazz, jint pid) {
    (void) env;
    (void) clazz;
    if (pid <= 0) return -1;

    int status = 0;
    pid_t result;
    do {
        result = waitpid((pid_t) pid, &status, 0);
    } while (result < 0 && errno == EINTR);

    if (result < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}
