
BINDIR := bin

# https://stackoverflow.com/a/18258352
rwildcard = $(foreach d,$(wildcard $(1:=/*)),$(call rwildcard,$d,$2) $(filter $(subst *,%,$2),$d))

JAVA_CP := json-20211205.jar:omz-common-release.jar:omz-netlib-nio-release.jar:omz-http-latest.jar
JAVAC_FLAGS := -Werror -Xlint:all,-processing
JAVA_PATH_SEPARATOR := $(strip $(shell java -XshowSettings:properties 2>&1 | grep path.separator | cut -d '=' -f2))


.PHONY: all
all: base http1 http2

.PHONY: base
base: $(BINDIR)/omz-proxy3.jar
.PHONY: http1
http1: $(BINDIR)/http1.jar
.PHONY: http2
http2: $(BINDIR)/http2.jar

.PHONY: clean
clean:
	rm -r $(BINDIR)/*

define pre_build
	@mkdir -p $(BINDIR)/$(1)
endef

define post_build
	@cp -r $(1)/main/resources/* $(BINDIR)/$(1)
	jar cf $(BINDIR)/$(1).jar -C $(BINDIR)/$(1) .
endef

$(BINDIR)/omz-proxy3.jar: $(call rwildcard,base/main/java,*.java)
	$(call pre_build,base)
	javac $(JAVAC_FLAGS) -d $(BINDIR)/base -cp "$(JAVA_CP)" $^
	$(call post_build,base)
	mv $(BINDIR)/base.jar $(BINDIR)/omz-proxy3.jar

$(BINDIR)/http1.jar: $(BINDIR)/omz-proxy3.jar $(call rwildcard,http1/main/scala,*.scala)
	$(call pre_build,http1)
	scalac -d $(BINDIR)/http1 -cp "$(JAVA_CP)$(JAVA_PATH_SEPARATOR)$(BINDIR)/omz-proxy3.jar" -explain $(filter-out $(BINDIR)/omz-proxy3.jar,$^)
	$(call post_build,http1)

$(BINDIR)/http2.jar: $(BINDIR)/omz-proxy3.jar $(call rwildcard,http2/main/scala,*.scala)
	$(call pre_build,http2)
	scalac -d $(BINDIR)/http2 -cp "$(JAVA_CP)$(JAVA_PATH_SEPARATOR)$(BINDIR)/omz-proxy3.jar" -explain $(filter-out $(BINDIR)/omz-proxy3.jar,$^)
	$(call post_build,http2)
