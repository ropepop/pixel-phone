#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <signal.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define REGISTRATION_TIMEOUT_MS 1500
#define REGISTRATION_POLL_MS 10
#define POPUP_SETTLE_MS 250
#define FOCUS_SETTLE_MS 300
#define IME_HIDE_SETTLE_MS 60
#define VALUE_SETTLE_MS 100
#define KEYBOARD_LEASE_MS 6000
#define HELPER_DEADLINE_MS 2700
#define TAP_POLL_MS 10
#define CLEAR_KEY_COUNT 8
#define MAX_DIGITS 8
#define MAX_EVENTS ((1 + CLEAR_KEY_COUNT + MAX_DIGITS) * 4)

static int keyboard_fd = -1;
static bool keyboard_created = false;

enum tap_result {
  TAP_RESULT_OK,
  TAP_RESULT_FAILED,
  TAP_RESULT_TIMEOUT,
};

enum ime_visibility_result {
  IME_VISIBILITY_HIDDEN,
  IME_VISIBILITY_VISIBLE,
  IME_VISIBILITY_FAILED,
  IME_VISIBILITY_TIMEOUT,
};

static void sleep_millis(long millis) {
  struct timespec requested = {
    .tv_sec = millis / 1000,
    .tv_nsec = (millis % 1000) * 1000000L,
  };
  while (nanosleep(&requested, &requested) != 0 && errno == EINTR) {
  }
}

static long long monotonic_millis(void) {
  struct timespec now;
  if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) {
    return -1;
  }
  return (long long)now.tv_sec * 1000LL + now.tv_nsec / 1000000LL;
}

static bool sleep_before_deadline(long millis, long long deadline_millis) {
  long long now = monotonic_millis();
  if (now < 0 || now >= deadline_millis) {
    return false;
  }
  long long remaining = deadline_millis - now;
  if (remaining < millis) {
    sleep_millis((long)remaining);
    return false;
  }
  sleep_millis(millis);
  return true;
}

static void secure_zero(void *value, size_t size) {
  volatile unsigned char *cursor = (volatile unsigned char *)value;
  while (size > 0) {
    *cursor++ = 0;
    size--;
  }
}

static void destroy_keyboard(void) {
  if (keyboard_fd >= 0) {
    if (keyboard_created) {
      ioctl(keyboard_fd, UI_DEV_DESTROY);
    }
    close(keyboard_fd);
    keyboard_fd = -1;
    keyboard_created = false;
  }
}

static void handle_signal(int signal_number) {
  destroy_keyboard();
  _exit(128 + signal_number);
}

static bool handoff_keyboard_lease(void) {
  pid_t child = fork();
  if (child < 0) {
    return false;
  }
  if (child == 0) {
    setsid();
    prctl(PR_SET_NAME, "ticket-kbd-lease", 0, 0, 0);
    signal(SIGHUP, SIG_IGN);
    signal(SIGINT, SIG_IGN);
    close(STDIN_FILENO);
    close(STDOUT_FILENO);
    close(STDERR_FILENO);
    sleep_millis(KEYBOARD_LEASE_MS);
    destroy_keyboard();
    _exit(0);
  }
  int parent_fd = keyboard_fd;
  keyboard_fd = -1;
  keyboard_created = false;
  close(parent_fd);
  return true;
}

static bool parse_coordinate(const char *value, int *coordinate) {
  char *end = NULL;
  errno = 0;
  long parsed = strtol(value, &end, 10);
  if (errno != 0 || end == value || *end != '\0' || parsed < 0 || parsed > 10000) {
    return false;
  }
  *coordinate = (int)parsed;
  return true;
}

static int read_digits(char digits[MAX_DIGITS + 1]) {
  char input[32];
  int result = -1;
  ssize_t size = read(STDIN_FILENO, input, sizeof(input) - 1);
  if (size <= 0) {
    secure_zero(input, sizeof(input));
    return result;
  }
  input[size] = '\0';
  size_t length = strcspn(input, "\r\n");
  if (length < 2 || length > MAX_DIGITS) {
    secure_zero(input, sizeof(input));
    return result;
  }
  for (size_t index = 0; index < length; index++) {
    if (input[index] < '0' || input[index] > '9') {
      secure_zero(input, sizeof(input));
      return result;
    }
    digits[index] = input[index];
  }
  digits[length] = '\0';
  result = (int)length;
  secure_zero(input, sizeof(input));
  return result;
}

static bool configure_key(int key_code) {
  return ioctl(keyboard_fd, UI_SET_KEYBIT, key_code) == 0;
}

static const int digit_key_codes[] = {
  KEY_0, KEY_1, KEY_2, KEY_3, KEY_4, KEY_5, KEY_6, KEY_7, KEY_8, KEY_9,
};

static bool create_keyboard(char device_name[UINPUT_MAX_NAME_SIZE]) {
  keyboard_fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
  if (keyboard_fd < 0) {
    return false;
  }
  if (ioctl(keyboard_fd, UI_SET_EVBIT, EV_KEY) != 0) {
    return false;
  }
  for (size_t index = 0; index < sizeof(digit_key_codes) / sizeof(digit_key_codes[0]); index++) {
    if (!configure_key(digit_key_codes[index])) {
      return false;
    }
  }
  if (!configure_key(KEY_BACKSPACE) || !configure_key(KEY_END)) {
    return false;
  }

  struct uinput_setup setup;
  memset(&setup, 0, sizeof(setup));
  snprintf(device_name, UINPUT_MAX_NAME_SIZE, "Ticket Root Keyboard %d", getpid());
  snprintf(setup.name, UINPUT_MAX_NAME_SIZE, "%s", device_name);
  setup.id.bustype = BUS_USB;
  setup.id.vendor = 0x18d1;
  setup.id.product = 0x57c8;
  setup.id.version = 1;
  if (ioctl(keyboard_fd, UI_DEV_SETUP, &setup) != 0 || ioctl(keyboard_fd, UI_DEV_CREATE) != 0) {
    return false;
  }
  keyboard_created = true;
  return true;
}

static bool keyboard_registered(const char *device_name, long long helper_deadline_millis) {
  char contents[16384];
  long long registration_deadline_millis = monotonic_millis() + REGISTRATION_TIMEOUT_MS;
  while (
    monotonic_millis() < registration_deadline_millis &&
    monotonic_millis() < helper_deadline_millis
  ) {
    int fd = open("/proc/bus/input/devices", O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
      ssize_t size = read(fd, contents, sizeof(contents) - 1);
      close(fd);
      if (size > 0) {
        contents[size] = '\0';
        if (strstr(contents, device_name) != NULL) {
          return true;
        }
      }
    }
    if (!sleep_before_deadline(REGISTRATION_POLL_MS, helper_deadline_millis)) {
      break;
    }
  }
  return false;
}

static enum tap_result run_input_tap(int x, int y, long long helper_deadline_millis) {
  char x_value[16];
  char y_value[16];
  snprintf(x_value, sizeof(x_value), "%d", x);
  snprintf(y_value, sizeof(y_value), "%d", y);
  pid_t child = fork();
  if (child < 0) {
    return TAP_RESULT_FAILED;
  }
  if (child == 0) {
    execl("/system/bin/input", "input", "tap", x_value, y_value, (char *)NULL);
    _exit(127);
  }
  int status = 0;
  while (true) {
    pid_t waited = waitpid(child, &status, WNOHANG);
    if (waited == child) {
      return WIFEXITED(status) && WEXITSTATUS(status) == 0
        ? TAP_RESULT_OK
        : TAP_RESULT_FAILED;
    }
    if (waited < 0 && errno != EINTR) {
      return TAP_RESULT_FAILED;
    }
    long long now = monotonic_millis();
    if (now < 0 || now >= helper_deadline_millis) {
      kill(child, SIGKILL);
      while (waitpid(child, &status, 0) < 0 && errno == EINTR) {
      }
      return TAP_RESULT_TIMEOUT;
    }
    if (!sleep_before_deadline(TAP_POLL_MS, helper_deadline_millis)) {
      kill(child, SIGKILL);
      while (waitpid(child, &status, 0) < 0 && errno == EINTR) {
      }
      return TAP_RESULT_TIMEOUT;
    }
  }
}

static enum tap_result hide_soft_keyboard(long long helper_deadline_millis) {
  pid_t child = fork();
  if (child < 0) {
    return TAP_RESULT_FAILED;
  }
  if (child == 0) {
    execl("/system/bin/input", "input", "keyevent", "4", (char *)NULL);
    _exit(127);
  }
  int status = 0;
  while (true) {
    pid_t waited = waitpid(child, &status, WNOHANG);
    if (waited == child) {
      return WIFEXITED(status) && WEXITSTATUS(status) == 0
        ? TAP_RESULT_OK
        : TAP_RESULT_FAILED;
    }
    if (waited < 0 && errno != EINTR) {
      return TAP_RESULT_FAILED;
    }
    long long now = monotonic_millis();
    if (now < 0 || now >= helper_deadline_millis) {
      kill(child, SIGKILL);
      while (waitpid(child, &status, 0) < 0 && errno == EINTR) {
      }
      return TAP_RESULT_TIMEOUT;
    }
    if (!sleep_before_deadline(TAP_POLL_MS, helper_deadline_millis)) {
      kill(child, SIGKILL);
      while (waitpid(child, &status, 0) < 0 && errno == EINTR) {
      }
      return TAP_RESULT_TIMEOUT;
    }
  }
}

static enum ime_visibility_result read_soft_keyboard_visibility(long long helper_deadline_millis) {
  static const char input_shown_marker[] = "mInputShown=true";
  static const char ime_hidden_marker[] = "mImeWindowVis=0";
  int output_pipe[2];
  if (pipe(output_pipe) != 0) {
    return IME_VISIBILITY_FAILED;
  }
  pid_t child = fork();
  if (child < 0) {
    close(output_pipe[0]);
    close(output_pipe[1]);
    return IME_VISIBILITY_FAILED;
  }
  if (child == 0) {
    close(output_pipe[0]);
    if (dup2(output_pipe[1], STDOUT_FILENO) < 0) {
      _exit(127);
    }
    close(output_pipe[1]);
    execl("/system/bin/dumpsys", "dumpsys", "input_method", (char *)NULL);
    _exit(127);
  }
  close(output_pipe[1]);
  int status = 0;
  int flags = fcntl(output_pipe[0], F_GETFL, 0);
  if (flags < 0 || fcntl(output_pipe[0], F_SETFL, flags | O_NONBLOCK) != 0) {
    kill(child, SIGKILL);
    while (waitpid(child, &status, 0) < 0 && errno == EINTR) {
    }
    close(output_pipe[0]);
    return IME_VISIBILITY_FAILED;
  }
  char contents[4096 + sizeof(input_shown_marker)];
  size_t carry = 0;
  bool input_shown = false;
  bool ime_window_hidden = false;
  bool child_exited = false;
  while (!child_exited) {
    while (true) {
      ssize_t size = read(output_pipe[0], contents + carry, sizeof(contents) - 1 - carry);
      if (size > 0) {
        size_t total = carry + (size_t)size;
        contents[total] = '\0';
        if (strstr(contents, input_shown_marker) != NULL) {
          input_shown = true;
        }
        if (strstr(contents, ime_hidden_marker) != NULL) {
          ime_window_hidden = true;
        }
        carry = total < sizeof(input_shown_marker) - 1 ? total : sizeof(input_shown_marker) - 1;
        memmove(contents, contents + total - carry, carry);
        continue;
      } else if (size < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
        kill(child, SIGKILL);
        while (waitpid(child, &status, 0) < 0 && errno == EINTR) {
        }
        close(output_pipe[0]);
        secure_zero(contents, sizeof(contents));
        return IME_VISIBILITY_FAILED;
      }
      break;
    }
    pid_t waited = waitpid(child, &status, WNOHANG);
    if (waited == child) {
      child_exited = true;
      break;
    }
    if (waited < 0 && errno != EINTR) {
      kill(child, SIGKILL);
      while (waitpid(child, &status, 0) < 0 && errno == EINTR) {
      }
      close(output_pipe[0]);
      secure_zero(contents, sizeof(contents));
      return IME_VISIBILITY_FAILED;
    }
    long long now = monotonic_millis();
    if (now < 0 || now >= helper_deadline_millis) {
      kill(child, SIGKILL);
      while (waitpid(child, &status, 0) < 0 && errno == EINTR) {
      }
      close(output_pipe[0]);
      secure_zero(contents, sizeof(contents));
      return IME_VISIBILITY_TIMEOUT;
    }
    if (!sleep_before_deadline(TAP_POLL_MS, helper_deadline_millis)) {
      kill(child, SIGKILL);
      while (waitpid(child, &status, 0) < 0 && errno == EINTR) {
      }
      close(output_pipe[0]);
      secure_zero(contents, sizeof(contents));
      return IME_VISIBILITY_TIMEOUT;
    }
  }
  while (true) {
    ssize_t size = read(output_pipe[0], contents + carry, sizeof(contents) - 1 - carry);
    if (size > 0) {
      size_t total = carry + (size_t)size;
      contents[total] = '\0';
      if (strstr(contents, input_shown_marker) != NULL) {
        input_shown = true;
      }
      if (strstr(contents, ime_hidden_marker) != NULL) {
        ime_window_hidden = true;
      }
      carry = total < sizeof(input_shown_marker) - 1 ? total : sizeof(input_shown_marker) - 1;
      memmove(contents, contents + total - carry, carry);
      continue;
    }
    if (size < 0 && errno == EINTR) {
      continue;
    }
    if (size < 0 && errno != EAGAIN && errno != EWOULDBLOCK) {
      close(output_pipe[0]);
      secure_zero(contents, sizeof(contents));
      return IME_VISIBILITY_FAILED;
    }
    break;
  }
  close(output_pipe[0]);
  bool command_ok = WIFEXITED(status) && WEXITSTATUS(status) == 0;
  secure_zero(contents, sizeof(contents));
  if (!command_ok) {
    return IME_VISIBILITY_FAILED;
  }
  return input_shown && !ime_window_hidden
    ? IME_VISIBILITY_VISIBLE
    : IME_VISIBILITY_HIDDEN;
}

static void append_event(struct input_event events[MAX_EVENTS], int *count, int type, int code, int value) {
  struct input_event *event = &events[*count];
  memset(event, 0, sizeof(*event));
  event->type = (__u16)type;
  event->code = (__u16)code;
  event->value = value;
  (*count)++;
}

static void append_key(struct input_event events[MAX_EVENTS], int *count, int key_code) {
  append_event(events, count, EV_KEY, key_code, 1);
  append_event(events, count, EV_SYN, SYN_REPORT, 0);
  append_event(events, count, EV_KEY, key_code, 0);
  append_event(events, count, EV_SYN, SYN_REPORT, 0);
}

static int digit_key_code(char digit) {
  return digit_key_codes[digit - '0'];
}

static bool inject_value(const char *digits, int digit_count) {
  struct input_event events[MAX_EVENTS];
  int event_count = 0;
  bool success = true;
  append_key(events, &event_count, KEY_END);
  for (int index = 0; index < CLEAR_KEY_COUNT; index++) {
    append_key(events, &event_count, KEY_BACKSPACE);
  }
  for (int index = 0; index < digit_count; index++) {
    append_key(events, &event_count, digit_key_code(digits[index]));
  }
  const unsigned char *cursor = (const unsigned char *)events;
  size_t remaining = (size_t)event_count * sizeof(struct input_event);
  while (remaining > 0) {
    ssize_t written = write(keyboard_fd, cursor, remaining);
    if (written < 0) {
      if (errno == EINTR) {
        continue;
      }
      success = false;
      break;
    }
    cursor += written;
    remaining -= (size_t)written;
  }
  secure_zero(events, sizeof(events));
  return success;
}

int main(int argc, char **argv) {
  long long started_millis = monotonic_millis();
  if (started_millis < 0) {
    return 54;
  }
  long long helper_deadline_millis = started_millis + HELPER_DEADLINE_MS;
  int open_x = -1;
  int open_y = -1;
  int input_x = -1;
  int input_y = -1;
  for (int index = 1; index < argc; index++) {
    if (strcmp(argv[index], "--open-x") == 0 && index + 1 < argc) {
      if (!parse_coordinate(argv[++index], &open_x)) {
        return 40;
      }
    } else if (strcmp(argv[index], "--open-y") == 0 && index + 1 < argc) {
      if (!parse_coordinate(argv[++index], &open_y)) {
        return 40;
      }
    } else if (strcmp(argv[index], "--input-x") == 0 && index + 1 < argc) {
      if (!parse_coordinate(argv[++index], &input_x)) {
        return 40;
      }
    } else if (strcmp(argv[index], "--input-y") == 0 && index + 1 < argc) {
      if (!parse_coordinate(argv[++index], &input_y)) {
        return 40;
      }
    } else {
      return 40;
    }
  }
  if (input_x < 0 || input_y < 0 || ((open_x < 0) != (open_y < 0))) {
    return 40;
  }

  char digits[MAX_DIGITS + 1] = {0};
  int digit_count = read_digits(digits);
  if (digit_count < 0) {
    secure_zero(digits, sizeof(digits));
    return 41;
  }

  signal(SIGTERM, handle_signal);
  signal(SIGINT, handle_signal);
  signal(SIGHUP, handle_signal);
  atexit(destroy_keyboard);

  char device_name[UINPUT_MAX_NAME_SIZE];
  memset(device_name, 0, sizeof(device_name));
  if (!create_keyboard(device_name)) {
    secure_zero(digits, sizeof(digits));
    return 42;
  }
  if (!keyboard_registered(device_name, helper_deadline_millis)) {
    secure_zero(digits, sizeof(digits));
    return monotonic_millis() >= helper_deadline_millis ? 54 : 50;
  }
  if (open_x >= 0) {
    enum tap_result open_result = run_input_tap(open_x, open_y, helper_deadline_millis);
    if (open_result == TAP_RESULT_TIMEOUT) {
      secure_zero(digits, sizeof(digits));
      return 54;
    }
    if (open_result != TAP_RESULT_OK) {
      secure_zero(digits, sizeof(digits));
      return 45;
    }
    if (!sleep_before_deadline(POPUP_SETTLE_MS, helper_deadline_millis)) {
      secure_zero(digits, sizeof(digits));
      return 54;
    }
  }
  enum tap_result input_result = run_input_tap(input_x, input_y, helper_deadline_millis);
  if (input_result == TAP_RESULT_TIMEOUT) {
    secure_zero(digits, sizeof(digits));
    return 54;
  }
  if (input_result != TAP_RESULT_OK) {
    secure_zero(digits, sizeof(digits));
    return 44;
  }
  if (!sleep_before_deadline(FOCUS_SETTLE_MS, helper_deadline_millis)) {
    secure_zero(digits, sizeof(digits));
    return 54;
  }
  enum ime_visibility_result ime_visibility = read_soft_keyboard_visibility(helper_deadline_millis);
  if (ime_visibility == IME_VISIBILITY_TIMEOUT) {
    secure_zero(digits, sizeof(digits));
    return 54;
  }
  if (ime_visibility == IME_VISIBILITY_FAILED) {
    secure_zero(digits, sizeof(digits));
    return 46;
  }
  if (ime_visibility == IME_VISIBILITY_VISIBLE) {
    enum tap_result keyboard_hide_result = hide_soft_keyboard(helper_deadline_millis);
    if (keyboard_hide_result == TAP_RESULT_TIMEOUT) {
      secure_zero(digits, sizeof(digits));
      return 54;
    }
    if (keyboard_hide_result != TAP_RESULT_OK) {
      secure_zero(digits, sizeof(digits));
      return 46;
    }
    if (!sleep_before_deadline(IME_HIDE_SETTLE_MS, helper_deadline_millis)) {
      secure_zero(digits, sizeof(digits));
      return 54;
    }
  }
  if (!inject_value(digits, digit_count)) {
    secure_zero(digits, sizeof(digits));
    return 51;
  }
  secure_zero(digits, sizeof(digits));
  if (!sleep_before_deadline(VALUE_SETTLE_MS, helper_deadline_millis)) {
    return 54;
  }
  if (!handoff_keyboard_lease()) {
    return 53;
  }
  return 0;
}
