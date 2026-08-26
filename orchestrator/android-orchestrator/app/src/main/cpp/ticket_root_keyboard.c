#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define POPUP_SETTLE_MS 250
#define FOCUS_SETTLE_MS 300
#define VALUE_SETTLE_MS 100
#define HELPER_DEADLINE_MS 2700
#define TAP_POLL_MS 10
#define KEY_EVENT_DELAY_MS "20"
#define CLEAR_KEY_COUNT 8
#define MAX_DIGITS 8
#define MAX_KEY_EVENT_ARGS (5 + CLEAR_KEY_COUNT + MAX_DIGITS + 1)

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

static enum tap_result run_input_tap(int x, int y, long long helper_deadline_millis) {
  char x_value[16];
  char y_value[16];
  snprintf(x_value, sizeof(x_value), "%d", x);
  snprintf(y_value, sizeof(y_value), "%d", y);
  pid_t parent_pid = getpid();
  pid_t child = fork();
  if (child < 0) {
    return TAP_RESULT_FAILED;
  }
  if (child == 0) {
    if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0 || getppid() != parent_pid) {
      _exit(126);
    }
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

static enum ime_visibility_result read_soft_keyboard_visibility(long long helper_deadline_millis) {
  static const char input_shown_marker[] = "mInputShown=true";
  static const char ime_hidden_marker[] = "mImeWindowVis=0";
  int output_pipe[2];
  if (pipe(output_pipe) != 0) {
    return IME_VISIBILITY_FAILED;
  }
  pid_t parent_pid = getpid();
  pid_t child = fork();
  if (child < 0) {
    close(output_pipe[0]);
    close(output_pipe[1]);
    return IME_VISIBILITY_FAILED;
  }
  if (child == 0) {
    if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0 || getppid() != parent_pid) {
      _exit(126);
    }
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

static const char *digit_key_names[] = {
  "KEYCODE_0", "KEYCODE_1", "KEYCODE_2", "KEYCODE_3", "KEYCODE_4",
  "KEYCODE_5", "KEYCODE_6", "KEYCODE_7", "KEYCODE_8", "KEYCODE_9",
};

/*
 * Android's own input command injects through InputManager's built-in virtual keyboard. Unlike a
 * temporary Linux virtual input device, it does not add or remove an InputDevice and therefore cannot
 * trigger the display-configuration transition that briefly hands brightness back to ViVi.
 */
static enum tap_result inject_value(
  const char *digits,
  int digit_count,
  long long helper_deadline_millis
) {
  char *arguments[MAX_KEY_EVENT_ARGS];
  int argument_count = 0;
  arguments[argument_count++] = "input";
  arguments[argument_count++] = "keyevent";
  arguments[argument_count++] = "--delay";
  arguments[argument_count++] = KEY_EVENT_DELAY_MS;
  arguments[argument_count++] = "KEYCODE_MOVE_END";
  for (int index = 0; index < CLEAR_KEY_COUNT; index++) {
    arguments[argument_count++] = "KEYCODE_DEL";
  }
  for (int index = 0; index < digit_count; index++) {
    arguments[argument_count++] = (char *)digit_key_names[digits[index] - '0'];
  }
  arguments[argument_count] = NULL;

  pid_t parent_pid = getpid();
  pid_t child = fork();
  if (child < 0) {
    return TAP_RESULT_FAILED;
  }
  if (child == 0) {
    if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0 || getppid() != parent_pid) {
      _exit(126);
    }
    execv("/system/bin/input", arguments);
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

int main(int argc, char **argv) {
  pid_t launching_parent_pid = getppid();
  if (
    launching_parent_pid <= 1 ||
    prctl(PR_SET_PDEATHSIG, SIGKILL) != 0 ||
    getppid() != launching_parent_pid
  ) {
    return 55;
  }
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
    secure_zero(digits, sizeof(digits));
    return 47;
  }
  enum tap_result inject_result = inject_value(digits, digit_count, helper_deadline_millis);
  if (inject_result == TAP_RESULT_TIMEOUT) {
    secure_zero(digits, sizeof(digits));
    return 54;
  }
  if (inject_result != TAP_RESULT_OK) {
    secure_zero(digits, sizeof(digits));
    return 51;
  }
  secure_zero(digits, sizeof(digits));
  if (!sleep_before_deadline(VALUE_SETTLE_MS, helper_deadline_millis)) {
    return 54;
  }
  return 0;
}
